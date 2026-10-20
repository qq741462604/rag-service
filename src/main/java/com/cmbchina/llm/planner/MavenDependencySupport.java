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
 * S1 的模块 POM 定位与已知公共依赖检查，实际依赖补丁仍由规划模型生成。
 * 仅解析明确关联的本地父链，不运行 Maven、不访问仓库、不扫描整个工程。
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
            Map<String, String> mappings = mappings();
            Map<String, String> required = new LinkedHashMap<>();
            JavaParser parser = new JavaParser();
            for (String source : sources) {
                parser.parse(source).getResult().ifPresent(unit -> unit.getImports().forEach(declaration -> {
                    String name = declaration.getNameAsString();
                    String providers = providers(name + (declaration.isAsterisk() ? ".*" : ""), mappings);
                    if (providers != null) {
                        required.putIfAbsent(providers, name);
                    }
                }));
            }
            Map<String, Element> available = compileDeclarations(context);
            for (Map.Entry<String, String> entry : required.entrySet()) {
                String preferred = entry.getKey().split("\\|")[0];
                boolean present = Arrays.stream(entry.getKey().split("\\|")).anyMatch(coordinate -> {
                    Element dependency = available.get(coordinate);
                    return dependency != null && (coordinate.equals(preferred)
                            || child(dependency, "exclusions") == null);
                });
                if (!present) {
                    errors.add("S1 缺少业务编译依赖: import " + entry.getValue()
                            + "；首选 " + entry.getKey().split("\\|")[0]
                            + "；局部补充模块 POM，已有已知提供方可复用: " + entry.getKey()
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
            rules.append("只允许局部补充入口所属模块 POM: ")
                    .append(workspaceService.relativePath(files.get(0))).append("；本地父 POM 只读。\n");
            Map<String, String> mappings = mappings();
            Set<String> hints = new LinkedHashSet<>();
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
                    String providers = providers(imports.group(1), mappings);
                    if (providers != null) {
                        hints.add(imports.group(1) + " -> " + providers);
                    }
                }
            }
            rules.append("当前模板已知候选坐标（按实际生成代码使用情况补充，不要求照单全加）: ")
                    .append(hints).append("\n当前模块及可访问本地父链的编译依赖声明: ")
                    .append(compileDependencies(context)).append('\n');
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

    public Set<String> compileDependencies(AgentContext context) {
        return new LinkedHashSet<>(compileDeclarations(context).keySet());
    }

    private Map<String, Element> compileDeclarations(AgentContext context) {
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
            if (scope.isEmpty() || "compile".equals(scope) || "provided".equals(scope) || "system".equals(scope)) {
                result.put(coordinate, dependency);
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
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(read(file)))).getDocumentElement();
        } catch (Exception exception) {
            throw new IllegalArgumentException("POM XML 无法安全解析: " + file + ": " + exception.getMessage(), exception);
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
