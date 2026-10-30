package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.config.AgentHomeService;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.workspace.WorkspaceService;
import com.github.javaparser.JavaParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S1/S2 的模块 POM 与依赖依据检查，实际依赖补丁仍由规划模型生成。
 * 仅解析本地父链及已声明依赖的本地 JAR，不运行 Maven、不访问远程仓库。
 *
 * @author summer
 * @date 2026-10-20 00:00:00
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MavenDependencySupport {

    private static final int MAX_POM_BYTES = 256 * 1024;
    private static final int MAX_PARENT_DEPTH = 8;
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;");

    private final WorkspaceService workspaceService;
    private final AgentHomeService agentHomeService;
    private final LocalMavenArtifacts localArtifacts;

    private static final String BASELINE = "dependency_initial_pom";

    public List<File> pomFiles(AgentContext context) {
        File module = modulePom(context);
        if (module == null) {
            throw new IllegalArgumentException("未在入口所属模块或 workspace 根目录找到 pom.xml，请确认目标工程配置");
        }
        List<File> files = new ArrayList<>();
        Set<File> visited = new LinkedHashSet<>();
        File current = module;
        while (current != null) {
            if (!visited.add(current) || files.size() >= MAX_PARENT_DEPTH) {
                throw new IllegalArgumentException("本地父 POM 链循环或超过 8 层，请检查 Maven 配置");
            }
            files.add(current);
            Element parent = child(parse(current), "parent");
            if (parent == null) {
                break;
            }
            Element relative = child(parent, "relativePath");
            String relativePath = relative == null ? "../pom.xml" : relative.getTextContent().trim();
            if (relativePath.isEmpty()) {
                break;
            }
            File candidate = new File(current.getParentFile(), relativePath);
            if (candidate.isDirectory()) {
                candidate = new File(candidate, "pom.xml");
            }
            try {
                candidate = workspaceService.resolve(candidate.getPath());
            } catch (IllegalArgumentException outsideWorkspace) {
                break;
            }
            if (!candidate.isFile() || !sameParent(parent, parse(candidate))) {
                break;
            }
            current = candidate;
        }
        return files;
    }

    public File modulePom(AgentContext context) {
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess() || !(observation.getResult() instanceof FileContent)) {
                continue;
            }
            String path = ((FileContent) observation.getResult()).getPath();
            boolean target = path != null && context.getSkills().stream()
                    .filter(skill -> skill.getTarget() != null && skill.getTarget().getClassName() != null)
                    .anyMatch(skill -> new File(path).getName().equals(skill.getTarget().getClassName() + ".java"));
            if (target) {
                File directory = workspaceService.resolve(path).getParentFile();
                File root = workspaceService.resolve(".");
                while (directory != null) {
                    File pom = new File(directory, "pom.xml");
                    if (pom.isFile()) {
                        return workspaceService.resolve(pom.getPath());
                    }
                    if (directory.equals(root)) {
                        break;
                    }
                    directory = directory.getParentFile();
                }
            }
        }
        File rootPom = workspaceService.resolve("pom.xml");
        return rootPom.isFile() ? rootPom : null;
    }

    public List<String> validate(AgentContext context, Collection<String> sources) {
        List<String> errors = new ArrayList<>();
        try {
            declaredDependencies(parse(modulePom(context))).forEach((coordinate, declarations) -> {
                if (declarations.size() > 1) {
                    errors.add("S1 模块 POM 依赖重复: " + coordinate
                            + "；保留一份适用的原有完整声明，局部删除冗余声明，不新增相同依赖。"
                            + "版本、scope 或 exclusions 不同的声明需先核实，禁止盲目合并配置。");
                }
            });
            Map<String, String> mappings = mappings();
            Set<String> imports = sourceImports(sources);
            Map<String, String> jarProviders = jarProviders(context, imports);
            Map<String, Element> available = compileDeclarations(context);
            for (String imported : imports) {
                String candidates = providers(imported, mappings);
                if (candidates == null || jarProviders.containsKey(imported)) {
                    continue;
                }
                String preferred = candidates.split("\\|")[0];
                boolean present = Arrays.stream(candidates.split("\\|")).anyMatch(coordinate -> {
                    Element dependency = available.get(coordinate);
                    return dependency != null && (coordinate.equals(preferred)
                            || child(dependency, "exclusions") == null);
                });
                if (!present) {
                    errors.add("S1 缺少业务编译依赖: import " + imported
                            + "；首选 " + preferred
                            + "；局部补充模块 POM，已有已知提供方可复用: " + candidates
                            + "。仅 dependencyManagement、test/runtime scope 不算已引入；"
                            + "带 exclusions 的传递提供方不直接视为满足，请核实后明确声明所需依赖。");
                }
            }
            if (!errors.isEmpty()) {
                log.info("S1 dependency pending module={} errors={}", modulePom(context), errors);
            }
        } catch (Exception exception) {
            errors.add("S1 依赖检查失败: " + exception.getMessage());
        }
        return errors;
    }

    public String buildRules(AgentContext context, String template) {
        StringBuilder rules = new StringBuilder("\nS1 Maven 依赖规则：\n");
        try {
            List<File> files = pomFiles(context);
            context.getVariables().putIfAbsent(BASELINE, read(files.get(0)));
            rules.append("只允许局部补充入口所属模块 POM: ")
                    .append(workspaceService.relativePath(files.get(0))).append("；本地父 POM 只读。\n");
            Map<String, String> mappings = mappings();
            Set<String> hints = new LinkedHashSet<>();
            Set<String> importsUsed = new LinkedHashSet<>();
            for (Observation observation : context.getObservations()) {
                if (!observation.isSuccess() || !(observation.getResult() instanceof FileContent)) {
                    continue;
                }
                FileContent content = (FileContent) observation.getResult();
                if (content.getPath() == null || !content.getPath().replace('\\', '/').endsWith(template)) {
                    continue;
                }
                String text = String.join("\n", content.getLines()).replaceAll("(?m)^\\d+: ?", "");
                Matcher imports = IMPORT.matcher(text);
                while (imports.find()) {
                    importsUsed.add(imports.group(1));
                    String providers = providers(imports.group(1), mappings);
                    if (providers != null) {
                        hints.add(imports.group(1) + " -> " + providers);
                    }
                }
            }
            rules.append("当前模板已知候选坐标（按实际生成代码使用情况补充，不要求照单全加）: ")
                    .append(hints).append("\n当前模块及可访问本地父链的编译依赖声明: ")
                    .append(compileDependencies(context)).append('\n');
            rules.append("已声明依赖本地 JAR 的精确类型证据（优先复用，无需再加提供同一类型的依赖）: ")
                    .append(jarProviders(context, importsUsed)).append('\n');
            rules.append(versionRules(importsUsed, mappings));
        } catch (Exception exception) {
            rules.append("依赖环境待确认: ").append(exception.getMessage()).append('\n');
        }
        return rules.append("由 AI 结合当前模板、真实 import 和已读 POM 识别缺项，不能只生成 Java。"
                + "已存在依赖不重复加；优先复用 properties/父 POM/BOM 管理版本，管理声明不等于引入依赖。"
                + "保留原版本、scope、exclusions 和插件，禁止升级已有依赖或覆盖 POM。"
                + "映射中竖线分隔的是首选坐标及已知传递提供方，不是要全部添加的清单。"
                + "带 exclusions 的传递提供方存在不确定性，检查排除项后优先明确声明缺失坐标。"
                + "静态报告不解析远程 POM/BOM 或激活 profiles，不是完整 effective-pom 或编译结果；"
                + "存在这些来源时先核实已有配置，不凭静态列表随意改写原有依赖。"
                + "映射以外的依赖仍需分析；私有包先查已有依赖/项目源码，无法确认坐标或版本时明确报告，不猜测。\n").toString();
    }

    private String versionRules(Collection<String> imports, Map<String, String> mappings) {
        Map<String, List<String>> versions = new LinkedHashMap<>();
        File repository = localArtifacts.repository();
        for (String imported : imports) {
            String candidates = providers(imported, mappings);
            if (candidates != null && versions.size() < 16) {
                String preferred = candidates.split("\\|")[0];
                if (!versions.containsKey(preferred)) {
                    versions.put(preferred, localArtifacts.versions(repository, preferred));
                }
            }
        }
        return "本地已有版本依据（最多列举16个坐标，每项16个版本；空列表不代表远程不存在）: " + versions
                + "；先复用目标版本管理，不能仅因本地有较新版本就升级。\n";
    }

    private Set<String> sourceImports(Collection<String> sources) {
        Set<String> imports = new LinkedHashSet<>();
        JavaParser parser = new JavaParser();
        for (String source : sources) {
            parser.parse(source).getResult().ifPresent(unit -> unit.getImports().forEach(declaration -> {
                String name = declaration.getNameAsString();
                if (declaration.isStatic()) {
                    // static import 的被提供方是声明类，不能把方法或字段当作类型。
                    if (!declaration.isAsterisk() && name.contains(".")) {
                        name = name.substring(0, name.lastIndexOf('.'));
                    }
                } else if (declaration.isAsterisk()) {
                    name += ".*";
                }
                imports.add(name);
            }));
        }
        return imports;
    }

    private Map<String, String> jarProviders(AgentContext context, Collection<String> imports) {
        Map<String, File> jars = new LinkedHashMap<>();
        File repository = localArtifacts.repository();
        compileDeclarations(context).forEach((coordinate, dependency) -> {
            File jar = "system".equals(text(dependency, "scope"))
                    ? new File(text(dependency, "systemPath"))
                    : localArtifacts.artifact(repository, coordinate, text(dependency, "version"), "jar");
            jars.put(coordinate, jar);
        });
        return localArtifacts.providers(context, jars, imports);
    }

    /** 新坐标须来自配置或本地制品，显式版本须有本地依据，禁止用包名猜私有组件。 */
    public void validatePomAdditions(AgentContext context, String before, String after) {
        context.getVariables().putIfAbsent(BASELINE, before);
        Set<String> known = new LinkedHashSet<>();
        Map<String, String> catalog;
        try {
            catalog = mappings();
            catalog.values().forEach(value -> known.addAll(Arrays.asList(value.split("\\|"))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取依赖映射失败: " + exception.getMessage(), exception);
        }
        Map<String, List<Element>> previous = declaredDependencies(parseXml(before));
        File repository = localArtifacts.repository();
        Map<String, String> properties = localProperties(context);
        Set<String> imports = observedImports(context);
        Map<String, String> evidence = jarProviders(context, imports);
        for (Map.Entry<String, List<Element>> entry : declaredDependencies(parseXml(after)).entrySet()) {
            if (previous.containsKey(entry.getKey())) {
                continue;
            }
            Element dependency = entry.getValue().get(0);
            String coordinate = text(dependency, "groupId") + ":" + text(dependency, "artifactId");
            String version = expand(text(dependency, "version"), properties);
            List<String> requested = new ArrayList<>();
            for (String imported : imports) {
                String candidates = providers(imported, catalog);
                if (candidates != null && candidates.split("\\|")[0].equals(coordinate)) {
                    requested.add(imported);
                }
            }
            // 测试引擎/Mockito inline 等提供运行能力，不能仅因类型已有就拒绝它们。
            if (!"test".equals(text(dependency, "scope")) && !requested.isEmpty()
                    && requested.stream().allMatch(imported -> evidence.containsKey(imported)
                    && !coordinate.equals(evidence.get(imported)))) {
                throw new IllegalArgumentException("已有编译依赖 JAR 已提供所需类型，无需重复新增 " + coordinate
                        + "；类型依据: " + evidence + "。保留已有提供方，只移除本计划冗余新增步骤。");
            }
            File pom = localArtifacts.artifact(repository, coordinate, version, "pom");
            File jar = localArtifacts.artifact(repository, coordinate, version, "jar");
            boolean localVersion = (pom != null && pom.isFile()) || (jar != null && jar.isFile());
            if (!known.contains(coordinate) && !localVersion) {
                throw new IllegalArgumentException("新增依赖无已确认坐标依据: " + coordinate
                        + "；先复用系统给出的本地 JAR 类型证据或配置映射，禁止根据 Java 包名猜私有坐标。");
            }
            if (!version.isEmpty() && !version.contains("${") && !localVersion) {
                throw new IllegalArgumentException("新增依赖显式版本缺少本地依据: " + coordinate + ":" + version
                        + "；核对目标 POM/properties/父 POM/BOM，禁止猜版本；不能删除版本来绕过 Maven 管理核实。");
            }
        }
    }

    public Set<String> compileDependencies(AgentContext context) {
        return new LinkedHashSet<>(compileDeclarations(context).keySet());
    }

    private Map<String, Element> compileDeclarations(AgentContext context) {
        return availableDeclarations(context, false);
    }

    private Map<String, String> localProperties(AgentContext context) {
        List<File> files = new ArrayList<>(pomFiles(context));
        Collections.reverse(files);
        Map<String, String> properties = new LinkedHashMap<>();
        for (File file : files) {
            Element declared = child(parse(file), "properties");
            if (declared != null) {
                for (Node node = declared.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element) {
                        properties.put(node.getNodeName(), node.getTextContent().trim());
                    }
                }
            }
        }
        return properties;
    }

    /** 只提供已声明的测试依赖线索，不把 starter 或静态父链误当作完整测试类路径。 */
    public String testDependencyRules(AgentContext context) {
        return "当前模块及可访问本地父链的编译/测试依赖声明: "
                + availableDeclarations(context, true).keySet() + "\n"
                + "先确认真实测试框架再生成 import；JUnit4 使用 org.junit.Test/org.junit.Assert，"
                + "JUnit5 使用 org.junit.jupiter.api.Test/Assertions，不能混用。"
                + "spring-boot-starter-test 不等于 junit:junit；Boot 2.7 的 starter 默认使用 Jupiter，"
                + "没有 JUnit4 依据时新测试沿用 Jupiter，不能凭习惯生成 org.junit.Test。"
                + "已有 JUnit4 测试保留框架、方法和断言，缺包时按已确认版本管理补 junit:junit test 依赖；"
                + "若当前 Surefire 使用 JUnit Platform，JUnit4 还需兼容的 junit-vintage-engine 才能执行，"
                + "不能只补编译包或凭空升级插件；确认当前执行器和版本依据后局部补测试依赖。"
                + "已有依赖未显式写 version 可能由父 POM/BOM 管理；进入编译阶段并不表示依赖缺版本。\n"
                + observedDependencyRules(context);
    }

    private String observedDependencyRules(AgentContext context) {
        Set<String> imports = observedImports(context);
        Map<String, String> hints = new LinkedHashMap<>();
        String versions;
        try {
            Map<String, String> mappings = mappings();
            for (String imported : imports) {
                String candidates = providers(imported, mappings);
                if (candidates != null) {
                    hints.put(imported, candidates);
                }
            }
            versions = versionRules(imports, mappings);
        } catch (Exception exception) {
            throw new IllegalArgumentException("依赖映射读取失败: " + exception.getMessage(), exception);
        }
        return "已读源码依赖候选（按需选用，不是全加清单）: " + hints
                + "\n已有编译依赖 JAR 精确类型证据（优先复用）: "
                + jarProviders(context, imports) + "\n" + versions;
    }

    private Set<String> observedImports(AgentContext context) {
        Set<String> imports = new LinkedHashSet<>();
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess() || !(observation.getResult() instanceof FileContent)) {
                continue;
            }
            FileContent content = (FileContent) observation.getResult();
            if (content.getPath() != null && (content.getPath().endsWith(".java")
                    || content.getPath().replace('\\', '/').contains("/predict-call-template-parts/"))) {
                Matcher matcher = IMPORT.matcher(String.join("\n", content.getLines()).replaceAll("(?m)^\\d+: ?", ""));
                while (matcher.find() && imports.size() < 512) {
                    imports.add(matcher.group(1));
                }
            }
        }
        return imports;
    }

    private Map<String, Element> availableDeclarations(AgentContext context, boolean includeTest) {
        List<File> files = new ArrayList<>(pomFiles(context));
        Collections.reverse(files);
        List<Element> projects = new ArrayList<>();
        Map<String, String> properties = new LinkedHashMap<>();
        for (File file : files) {
            Element project = parse(file);
            projects.add(project);
            Element declaredProperties = child(project, "properties");
            if (declaredProperties != null) {
                for (Node node = declaredProperties.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element) {
                        properties.put(node.getNodeName(), node.getTextContent().trim());
                    }
                }
            }
        }
        Map<String, Element> managed = new LinkedHashMap<>();
        Map<String, Element> declared = new LinkedHashMap<>();
        for (Element project : projects) {
            collectDependencies(child(child(project, "dependencyManagement"), "dependencies"), properties, managed);
            collectDependencies(child(project, "dependencies"), properties, declared);
        }
        Map<String, Element> result = new LinkedHashMap<>();
        declared.forEach((coordinate, dependency) -> {
            String scope = text(dependency, "scope");
            if (scope.isEmpty()) {
                scope = text(managed.get(coordinate), "scope");
            }
            scope = expand(scope, properties);
            if (scope.isEmpty() || "compile".equals(scope) || "provided".equals(scope) || "system".equals(scope)
                    || (includeTest && "test".equals(scope))) {
                Element resolved = (Element) dependency.cloneNode(true);
                for (String name : Arrays.asList("version", "scope", "systemPath")) {
                    String value = text(dependency, name);
                    if (value.isEmpty() && !"systemPath".equals(name)) {
                        value = text(managed.get(coordinate), name);
                    }
                    Element field = child(resolved, name);
                    if (field == null && !value.isEmpty()) {
                        field = resolved.getOwnerDocument().createElement(name);
                        resolved.appendChild(field);
                    }
                    if (field != null) {
                        field.setTextContent(expand(value, properties));
                    }
                }
                result.put(coordinate, resolved);
            }
        });
        return result;
    }

    private void collectDependencies(Element dependencies, Map<String, String> properties,
                                     Map<String, Element> result) {
        if (dependencies == null) {
            return;
        }
        for (Node node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element) || !"dependency".equals(node.getNodeName())) {
                continue;
            }
            Element dependency = (Element) node;
            String type = expand(text(dependency, "type"), properties);
            if (!(type.isEmpty() || "jar".equals(type)) || !text(dependency, "classifier").isEmpty()) {
                continue;
            }
            String coordinate = expand(text(dependency, "groupId"), properties) + ":"
                    + expand(text(dependency, "artifactId"), properties);
            result.put(coordinate, dependency);
        }
    }

    private Map<String, String> mappings() throws Exception {
        File file = agentHomeService.resolve(".ai/dependency-mappings.properties");
        Properties properties = new Properties();
        properties.load(new StringReader(read(file)));
        Map<String, String> result = new LinkedHashMap<>();
        for (String prefix : properties.stringPropertyNames()) {
            String value = properties.getProperty(prefix).trim();
            if (!prefix.endsWith(".") || !value.matches("[\\w.-]+:[\\w.-]+(\\|[\\w.-]+:[\\w.-]+)*")) {
                throw new IllegalArgumentException("依赖映射格式错误: " + prefix);
            }
            result.put(prefix, value);
        }
        return result;
    }

    private String providers(String imported, Map<String, String> mappings) {
        return mappings.keySet().stream().filter(imported::startsWith)
                .max(java.util.Comparator.comparingInt(String::length)).map(mappings::get).orElse(null);
    }

    private Element parse(File file) {
        return parseXml(read(file));
    }

    public void validateTestPom(AgentContext context, String before, String after, boolean generation,
                                Set<String> missingVersions) {
        Element previous = parseXml(before);
        Element next = parseXml(after);
        Map<String, List<Element>> oldDependencies = declaredDependencies(previous);
        Map<String, List<Element>> newDependencies = declaredDependencies(next);
        Set<String> removable = generation ? Collections.emptySet() : removableDependencies(context, before);
        File repository = localArtifacts.repository();
        Map<String, String> properties = localProperties(context);
        validatePomAdditions(context, before, after);
        newDependencies.forEach((coordinate, declarations) -> {
            if (declarations.size() != 1) {
                throw new IllegalArgumentException("修复后的 POM 依赖仍重复: " + coordinate
                        + "；一次性移除冗余声明，oldText 必须包含唯一上下文");
            }
        });
        for (Map.Entry<String, List<Element>> dependency : oldDependencies.entrySet()) {
            List<Element> originals = dependency.getValue();
            List<Element> replacements = newDependencies.get(dependency.getKey());
            Element replacement = replacements == null ? null : replacements.get(0);
            if (originals.size() > 1) {
                boolean retained = replacement != null && originals.stream()
                        .anyMatch(original -> xmlSignature(original).equals(xmlSignature(replacement)));
                if (generation || !retained) {
                    throw new IllegalArgumentException("S2 去重只能保留一份原有完整依赖声明，禁止同时改版本、scope、"
                            + "exclusions 或拼接新声明: " + dependency.getKey());
                }
                continue;
            }
            Element original = originals.get(0);
            // 只撤回本任务新增、且本次 Maven 明确报告缺版本/找不到制品的声明，原有依赖仍保护。
            if (replacement == null && removable.contains(dependency.getKey())) {
                continue;
            }
            boolean fillMissingVersion = !generation && replacement != null
                    && missingVersions.contains(text(original, "groupId") + ":" + text(original, "artifactId"))
                    && isVersionOnlyAddition(original, replacement);
            if (fillMissingVersion) {
                String coordinate = text(original, "groupId") + ":" + text(original, "artifactId");
                String version = expand(text(replacement, "version"), properties);
                File metadata = localArtifacts.artifact(repository, coordinate, version, "pom");
                File jar = localArtifacts.artifact(repository, coordinate, version, "jar");
                if ((metadata == null || !metadata.isFile()) && (jar == null || !jar.isFile())) {
                    throw new IllegalArgumentException("补充版本缺少本地制品或 POM 依据: " + coordinate
                            + "；不能以猜测版本修复；优先核对已有 JAR 是否提供类型及本任务可撤回的错误依赖。");
                }
            }
            if (replacement == null
                    || !xmlSignature(original).equals(xmlSignature(replacement)) && !fillMissingVersion) {
                throw new IllegalArgumentException("S2 不得删除或改变已有依赖: " + dependency.getKey()
                        + "；未显式声明版本不等于缺少版本，可能由父 POM/BOM 管理。"
                        + "只有 Maven 明确报告该依赖 version is missing 时才允许补空版本；"
                        + "org.junit 缺包应核对 JUnit4/5 import 与实际测试框架，不能修改 Mockito 版本替代修复。");
            }
        }
        if (generation) {
            Element dependencies = child(next, "dependencies");
            if (dependencies != null) {
                for (Node node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element && "dependency".equals(node.getNodeName())) {
                        Element dependency = (Element) node;
                        if (!oldDependencies.containsKey(dependencyKey(dependency))
                                && !"test".equals(text(dependency, "scope"))) {
                            throw new IllegalArgumentException("S2 生成阶段只能新增 test scope 依赖");
                        }
                    }
                }
            }
        }
        Element oldContainer = child(previous, "dependencies");
        Element newContainer = child(next, "dependencies");
        if (oldContainer != null) {
            previous.removeChild(oldContainer);
        }
        if (newContainer != null) {
            next.removeChild(newContainer);
        }
        if (!xmlSignature(previous).equals(xmlSignature(next))) {
            throw new IllegalArgumentException("S2 只允许局部补充模块直接依赖及修复 Maven 明确报错的缺失版本，"
                    + "不能改插件、属性、父 POM 或跳过测试配置");
        }
    }

    public Set<String> removableDependencies(AgentContext context, String currentPom) {
        return removableDependencies(context, currentPom, String.join("\n", context.getVerifyErrors()));
    }

    public boolean canRepairDependencyFailure(AgentContext context, List<String> errors) {
        return !removableDependencies(context, read(modulePom(context)), String.join("\n", errors)).isEmpty();
    }

    private Set<String> removableDependencies(AgentContext context, String currentPom, String errors) {
        Object baseline = context.getVariables().get(BASELINE);
        if (!(baseline instanceof String)) {
            return Collections.emptySet();
        }
        Map<String, List<Element>> original = declaredDependencies(parseXml((String) baseline));
        Set<String> failed = new LinkedHashSet<>();
        Matcher missing = Pattern.compile("(?i)(?:could not find artifact|failure to find)\\s+([\\w.-]+:[\\w.-]+):"
                + "|dependencies\\.dependency\\.version'\\s+for\\s+([\\w.-]+:[\\w.-]+):[^\\s]+\\s+is missing")
                .matcher(errors);
        while (missing.find()) {
            failed.add(missing.group(1) == null ? missing.group(2) : missing.group(1));
        }
        Set<String> result = new LinkedHashSet<>();
        declaredDependencies(parseXml(currentPom)).forEach((key, declarations) -> {
            Element dependency = declarations.get(0);
            String coordinate = text(dependency, "groupId") + ":" + text(dependency, "artifactId");
            if (!original.containsKey(key) && failed.contains(coordinate) && declarations.size() == 1) {
                result.add(key);
            }
        });
        return result;
    }

    public String repairDependencyRules(AgentContext context) {
        return "本次 Maven 点名失败且本任务新增、允许核实后撤回的依赖: "
                + removableDependencies(context, read(modulePom(context)))
                + "；原有依赖禁止删除或改版本。先复用已有 JAR 提供的类型，不能补猜测的坐标/版本；"
                + "撤回不代表验证通过，仍须通过 S1 契约、编译及本次真实测试。\n";
    }

    /** 仅允许将空缺版本补为非空值，其余依赖结构保持一致，不放开已有版本升级。 */
    private boolean isVersionOnlyAddition(Element before, Element after) {
        if (!text(before, "version").isEmpty() || text(after, "version").isEmpty()) {
            return false;
        }
        Element original = (Element) before.cloneNode(true);
        Element replacement = (Element) after.cloneNode(true);
        Element oldVersion = child(original, "version");
        if (oldVersion != null) {
            original.removeChild(oldVersion);
        }
        replacement.removeChild(child(replacement, "version"));
        return xmlSignature(original).equals(xmlSignature(replacement));
    }

    private Map<String, List<Element>> declaredDependencies(Element project) {
        Map<String, List<Element>> result = new LinkedHashMap<>();
        Element dependencies = child(project, "dependencies");
        if (dependencies != null) {
            for (Node node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element) {
                    Element dependency = (Element) node;
                    String key = dependencyKey(dependency);
                    if (!"dependency".equals(node.getNodeName())) {
                        throw new IllegalArgumentException("POM 依赖结构异常: " + key);
                    }
                    result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(dependency);
                }
            }
        }
        return result;
    }

    private String dependencyKey(Element dependency) {
        String type = text(dependency, "type");
        return text(dependency, "groupId") + ":" + text(dependency, "artifactId") + ":"
                + (type.isEmpty() ? "jar" : type) + ":" + text(dependency, "classifier");
    }

    private String xmlSignature(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            return node.getTextContent().trim();
        }
        if (!(node instanceof Element)) {
            return "";
        }
        StringBuilder value = new StringBuilder("<").append(node.getNodeName());
        Map<String, String> attributes = new java.util.TreeMap<>();
        for (int index = 0; index < node.getAttributes().getLength(); index++) {
            Node attribute = node.getAttributes().item(index);
            attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        value.append(attributes).append('>');
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            value.append(xmlSignature(child));
        }
        return value.append("</").append(node.getNodeName()).append('>').toString();
    }

    private Element parseXml(String source) {
        try {
            if (source.length() > MAX_POM_BYTES) {
                throw new IllegalArgumentException("POM 超过 256 KiB");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(source))).getDocumentElement();
        } catch (Exception exception) {
            throw new IllegalArgumentException("POM XML 无法安全解析: " + exception.getMessage(), exception);
        }
    }

    private String read(File file) {
        try {
            if (!file.isFile() || file.length() > MAX_POM_BYTES) {
                throw new IllegalArgumentException("文件不存在或超过 256KB: " + file);
            }
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("读取失败: " + file, exception);
        }
    }

    private boolean sameParent(Element reference, Element candidate) {
        Element inherited = child(candidate, "parent");
        String group = text(candidate, "groupId");
        String version = text(candidate, "version");
        return text(reference, "artifactId").equals(text(candidate, "artifactId"))
                && text(reference, "groupId").equals(group.isEmpty() ? text(inherited, "groupId") : group)
                && text(reference, "version").equals(version.isEmpty() ? text(inherited, "version") : version);
    }

    private String expand(String value, Map<String, String> properties) {
        String result = value;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && result.contains("${"); depth++) {
            String before = result;
            for (Map.Entry<String, String> property : properties.entrySet()) {
                result = result.replace("${" + property.getKey() + "}", property.getValue());
            }
            if (result.equals(before)) {
                break;
            }
        }
        return result;
    }

    private String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    private Element child(Element parent, String name) {
        if (parent != null) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element && name.equals(node.getNodeName())) {
                    return (Element) node;
                }
            }
        }
        return null;
    }
}
