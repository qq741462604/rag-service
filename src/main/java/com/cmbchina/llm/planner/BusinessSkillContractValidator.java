package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.skill.Skill;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.verify.TestSourceInspector;
import com.cmbchina.llm.workspace.WorkspaceService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 普通业务 Skill 的 S1 产物完整性检查；只读本任务已知文件，不替代真实行为测试。
 *
 * @author summer
 * @date 2026-11-10 00:00:00
 */
@Component
@RequiredArgsConstructor
public class BusinessSkillContractValidator {

    private final WorkspaceService workspaceService;
    private final TestSourceInspector inspector;
    private final MavenDependencySupport dependencySupport;

    public boolean supports(AgentContext context) {
        return context.getSkills().stream().anyMatch(skill -> supported(skill.getName()));
    }

    private boolean supported(String name) {
        return Arrays.asList("external-api", "field-slicing", "date-transform", "ip-parser", "ip解析增强", "rtd-results")
                .contains(name);
    }

    public List<String> validate(AgentContext context) {
        List<String> errors = new ArrayList<>();
        if (!supports(context)) {
            return errors;
        }
        Map<String, File> paths = knownArtifacts(context);
        Map<String, CompilationUnit> units = new LinkedHashMap<>();
        Map<String, String> configs = new LinkedHashMap<>();
        for (Map.Entry<String, File> path : paths.entrySet()) {
            if (!path.getValue().isFile() || path.getValue().length() > 256 * 1024) {
                errors.add("S1 已知产物不存在或超过256KB检查上限: " + path.getKey());
                continue;
            }
            try {
                String source = inspector.read(path.getValue());
                if (path.getKey().endsWith(".java")) {
                    units.put(path.getKey(), inspector.parse(source));
                } else {
                    configs.put(path.getKey(), source);
                }
            } catch (RuntimeException exception) {
                errors.add(path.getKey() + ": " + exception.getMessage());
            }
        }
        validateSkills(context, units, configs, errors);
        if (errors.isEmpty()) {
            errors.addAll(dependencySupport.validate(context, units.values().stream().map(Object::toString)
                    .collect(Collectors.toList())));
        }
        return errors;
    }

    public List<String> artifactPaths(AgentContext context) {
        return new ArrayList<>(knownArtifacts(context).keySet());
    }

    private Map<String, File> knownArtifacts(AgentContext context) {
        Map<String, File> paths = new LinkedHashMap<>();
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess()) {
                continue;
            }
            String path = observation.getResult() instanceof FileContent ? ((FileContent) observation.getResult()).getPath()
                    : observation.getResult() instanceof FileOperationResult ? ((FileOperationResult) observation.getResult()).getPath()
                    : observation.getInput() instanceof ToolInput ? ((ToolInput) observation.getInput()).getPath() : null;
            if (path == null) {
                continue;
            }
            try {
                File file = workspaceService.resolve(path);
                String relative = workspaceService.relativePath(file).replace('\\', '/');
                if (relative.contains("src/main/") && (relative.endsWith(".java")
                        || relative.endsWith(".yml") || relative.endsWith(".yaml"))) {
                    paths.put(relative, file);
                }
            } catch (IllegalArgumentException referenceOutsideWorkspace) {
                // 本项目 Skill/模板为只读参考，不是目标业务产物。
            }
        }
        return paths;
    }

    private void validateSkills(AgentContext context, Map<String, CompilationUnit> units,
                                Map<String, String> configs, List<String> errors) {
        for (Skill skill : context.getSkills()) {
            if (!supported(skill.getName()) || skill.getTarget() == null) {
                continue;
            }
            List<CompilationUnit> targets = units.values().stream().filter(unit -> unit.getTypes().stream()
                    .anyMatch(type -> type.getNameAsString().equals(skill.getTarget().getClassName())))
                    .collect(Collectors.toList());
            if (targets.size() != 1) {
                errors.add(skill.getName() + " 必须先定位并完整读取唯一真实入口 " + skill.getTarget().getClassName());
                continue;
            }
            CompilationUnit target = targets.get(0);
            TypeDeclaration<?> owner = target.getTypes().stream().filter(type ->
                    type.getNameAsString().equals(skill.getTarget().getClassName())).findFirst().get();
            List<MethodDeclaration> methods = reachable(owner, skill.getTarget().getMethodName());
            if (methods.isEmpty()) {
                errors.add(skill.getName() + " 入口方法缺失: " + skill.getTarget().getMethodName());
                continue;
            }
            List<MethodCallExpr> calls = methods.stream().flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                    .collect(Collectors.toList());
            if ("external-api".equals(skill.getName())) {
                units.entrySet().stream().filter(entry -> entry.getValue() == target).findFirst().ifPresent(entry -> {
                    Object baseline = context.getVariables().get("business_initial_source:" + entry.getKey());
                    if (baseline instanceof String) {
                        errors.addAll(lifecycleErrors(inspector.parse((String) baseline), target));
                    }
                });
                validateExternal(target, methods, units, configs, errors);
            } else if ("rtd-results".equals(skill.getName())) {
                validateResults(context, calls, errors);
            } else {
                if (calls.stream().noneMatch(call -> call.getNameAsString().equals("putDataAndOriginData"))) {
                    errors.add(skill.getName() + " 入口调用链缺少真实 putDataAndOriginData 字段写回");
                }
                if ("field-slicing".equals(skill.getName()) && calls.stream().noneMatch(call ->
                        call.getNameAsString().equals("substring"))) {
                    errors.add("field-slicing 尚未接入真实 substring 截取调用，先完成业务再生成测试");
                }
                if ("date-transform".equals(skill.getName()) && calls.stream().noneMatch(call ->
                        Arrays.asList("parse", "parseDate", "parseDateTime").contains(call.getNameAsString()))) {
                    errors.add("date-transform 尚未接入真实日期解析调用，禁止仅添加空方法或格式常量");
                }
                if (Arrays.asList("ip-parser", "ip解析增强").contains(skill.getName())) {
                    for (String field : Arrays.asList("IP_CITY", "IP_COUNTRY", "IP_PRO")) {
                        if (calls.stream().noneMatch(call -> call.getNameAsString().equals("putDataAndOriginData")
                                && call.getArguments().size() == 2 && call.getArgument(0).toString().equals(field))) {
                            errors.add("IP Skill 缺少真实写回字段: " + field);
                        }
                    }
                    if (calls.stream().noneMatch(call -> call.getNameAsString().equals("findCity"))) {
                        errors.add("IP Skill 尚未接入真实 GeoIpService.findCity 调用");
                    }
                }
            }
        }
    }

    /** 修改前保存真实入口快照，不能把原有 Spring 注解误判为本轮新增。 */
    public void validatePreview(AgentContext context, File file, String before, String after) {
        boolean target = context.getSkills().stream().anyMatch(skill -> "external-api".equals(skill.getName())
                && skill.getTarget() != null && file.getName().equals(skill.getTarget().getClassName() + ".java"));
        if (!target || before == null || after == null) {
            return;
        }
        String key = "business_initial_source:" + workspaceService.relativePath(file).replace('\\', '/');
        Object baseline = context.getVariables().get(key);
        String original = baseline instanceof String ? (String) baseline : before;
        List<String> errors = lifecycleErrors(inspector.parse(original), inspector.parse(after));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        context.getVariables().putIfAbsent(key, original);
    }

    private List<String> lifecycleErrors(CompilationUnit before, CompilationUnit after) {
        List<String> errors = new ArrayList<>();
        for (TypeDeclaration<?> old : before.getTypes()) {
            TypeDeclaration<?> next = after.getTypes().stream().filter(type -> type.getNameAsString()
                    .equals(old.getNameAsString())).findFirst().orElse(null);
            if (next == null || !old.isClassOrInterfaceDeclaration() || !next.isClassOrInterfaceDeclaration()) {
                errors.add("external-api 必须保留真实入口类型");
                continue;
            }
            if (!old.asClassOrInterfaceDeclaration().getExtendedTypes().toString().equals(
                    next.asClassOrInterfaceDeclaration().getExtendedTypes().toString())
                    || !old.asClassOrInterfaceDeclaration().getConstructors().toString().equals(
                    next.asClassOrInterfaceDeclaration().getConstructors().toString())) {
                errors.add("external-api 禁止改变 Target 继承关系或构造器，按已有依赖获取方式接入 Client");
            }
            for (String annotation : Arrays.asList("Component", "Service", "Configuration", "RequiredArgsConstructor",
                    "AllArgsConstructor", "NoArgsConstructor", "Autowired")) {
                List<String> prior = old.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                        .filter(item -> annotation.equals(item.getName().getIdentifier())).map(Object::toString)
                        .collect(Collectors.toList());
                List<String> current = next.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                        .filter(item -> annotation.equals(item.getName().getIdentifier())).map(Object::toString)
                        .collect(Collectors.toList());
                if (!prior.equals(current)) {
                    errors.add("external-api 禁止增删/改变 Target 原有组件或注入注解 @" + annotation);
                }
            }
        }
        return errors;
    }

    /** 只沿同类无显式接收者/this的方法调用展开，私有 helper 未被入口调用不能算产物完成。 */
    private List<MethodDeclaration> reachable(TypeDeclaration<?> owner, String name) {
        Set<MethodDeclaration> result = owner.getMethods().stream().filter(method -> method.getNameAsString().equals(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (int depth = 0; depth < owner.getMethods().size(); depth++) {
            Set<String> names = result.stream().flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                    .filter(call -> !call.getScope().isPresent() || call.getScope().get().isThisExpr())
                    .map(MethodCallExpr::getNameAsString).collect(Collectors.toSet());
            if (!result.addAll(owner.getMethods().stream().filter(method -> names.contains(method.getNameAsString()))
                    .collect(Collectors.toList()))) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    private void validateResults(AgentContext context, List<MethodCallExpr> calls, List<String> errors) {
        Set<String> keys = calls.stream().filter(call -> call.getNameAsString().equals("put")
                && call.getScope().map(scope -> scope.toString().equals("rtdResults")).orElse(false)
                && call.getArguments().size() == 2 && call.getArgument(0).isStringLiteralExpr()
                && call.findAncestor(com.github.javaparser.ast.stmt.TryStmt.class).isPresent())
                .map(call -> call.getArgument(0).asStringLiteralExpr().asString()).collect(Collectors.toSet());
        if (keys.isEmpty()) {
            errors.add("rtd-results 必须在原有 try 边界内增量写回 rtdResults");
        }
        String task = context.getTask().toLowerCase(java.util.Locale.ROOT);
        for (String key : Arrays.asList("procVars", "rtqVars", "externalVars", "predict_rescale_score")) {
            if (task.contains(key.toLowerCase(java.util.Locale.ROOT)) && !keys.contains(key)) {
                errors.add("rtd-results 缺少本需求返回字段: " + key);
            }
        }
        if ((task.contains("模型评分") || task.contains("评分返回")) && !keys.contains("predict_rescale_score")) {
            errors.add("rtd-results 缺少模型评分返回字段 predict_rescale_score");
        }
    }

    private void validateExternal(CompilationUnit target, List<MethodDeclaration> methods,
                                  Map<String, CompilationUnit> units, Map<String, String> configs, List<String> errors) {
        Set<String> references = target.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).stream()
                .map(type -> type.getNameAsString()).collect(Collectors.toSet());
        List<TypeDeclaration<?>> clients = units.values().stream().flatMap(unit -> unit.getTypes().stream())
                .filter(type -> type.getNameAsString().endsWith("ApiClient") && references.contains(type.getNameAsString()))
                .collect(Collectors.toList());
        if (clients.isEmpty()) {
            errors.add("external-api 尚缺入口真实引用的 XxxApiClient；先完成配置、Client、DTO、工厂及入口调用");
            return;
        }
        for (TypeDeclaration<?> client : clients) {
            Set<String> clientFields = target.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getType().isClassOrInterfaceType() && variable.getType()
                            .asClassOrInterfaceType().getNameAsString().equals(client.getNameAsString()))
                    .map(variable -> variable.getNameAsString()).collect(Collectors.toSet());
            Set<String> invoked = methods.stream().flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                    .filter(call -> call.getScope().map(scope -> clientFields.contains(scope.toString())
                            || scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr()
                            && clientFields.contains(scope.asFieldAccessExpr().getNameAsString())).orElse(false))
                    .map(MethodCallExpr::getNameAsString).collect(Collectors.toSet());
            if (invoked.isEmpty()) {
                errors.add("external-api 入口尚未调用 " + client.getNameAsString() + " 的真实方法");
            }
            if (methods.stream().flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                    .noneMatch(call -> call.getNameAsString().equals("putDataAndOriginData"))) {
                errors.add("external-api 入口尚未通过 putDataAndOriginData 写回接口业务结果");
            }
            List<MethodDeclaration> business = client.getMethods().stream().filter(method -> invoked.contains(method.getNameAsString()))
                    .collect(Collectors.toList());
            if (business.isEmpty() || business.stream().anyMatch(method -> !knownReturnType(method.getType().asString(), units))) {
                errors.add("external-api 必须完成真实 Client 方法及其已读取的 DTO 返回类型，不能只生成 Client 构造器");
            }
            Set<String> templates = variablesOfType(client, java.util.Collections.singleton("RestTemplate"));
            if (business.stream().anyMatch(method -> reachable(client, method.getNameAsString()).stream()
                    .flatMap(value -> value.findAll(MethodCallExpr.class).stream()).noneMatch(call ->
                            Arrays.asList("exchange", "execute", "getForObject", "getForEntity", "postForObject",
                                    "postForEntity", "patchForObject").contains(call.getNameAsString())
                                    && call.getScope().map(scope -> templates.contains(receiverName(scope))).orElse(false)))) {
                errors.add("external-api 被入口调用的 Client 方法必须接入真实 RestTemplate 请求，不能返回 null/常量占位");
            }
            Set<String> configNames = client.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).stream()
                    .map(type -> type.getNameAsString()).filter(name -> name.endsWith("ApiConfig")).collect(Collectors.toSet());
            List<TypeDeclaration<?>> apiConfigs = units.values().stream().flatMap(unit -> unit.getTypes().stream())
                    .filter(type -> configNames.contains(type.getNameAsString())).collect(Collectors.toList());
            if (apiConfigs.isEmpty()) {
                errors.add("external-api 尚缺 Client 实际引用的 ApiConfig，必须先读取复用或完成生成");
            }
            for (TypeDeclaration<?> config : apiConfigs) {
                validateApiProperties(config, configs, errors);
            }
            boolean factory = units.values().stream().flatMap(unit -> unit.findAll(MethodDeclaration.class).stream())
                    .anyMatch(method -> method.getType().asString().endsWith("RestTemplate")
                            && method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).map(owner ->
                            hasConfiguredTimeout(method, owner, configNames, "setConnectTimeout")
                                    && hasConfiguredTimeout(method, owner, configNames, "setReadTimeout")).orElse(false)
                            && method.getAnnotations().stream().anyMatch(annotation -> annotation.getName().getIdentifier().equals("Bean"))
                            && method.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("setConnectTimeout"))
                            && method.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("setReadTimeout")));
            if (!factory) {
                errors.add("external-api 尚缺已读取且真实设置连接/读取超时的 RestTemplate Bean，不能将其留到 S2 补业务");
            }
        }
    }

    private Set<String> variablesOfType(TypeDeclaration<?> owner, Set<String> types) {
        return owner.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .filter(variable -> variable.getType().isClassOrInterfaceType() && types.contains(variable.getType()
                        .asClassOrInterfaceType().getNameAsString()))
                .map(variable -> variable.getNameAsString()).collect(Collectors.toSet());
    }

    private String receiverName(com.github.javaparser.ast.expr.Expression scope) {
        return scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr()
                ? scope.asFieldAccessExpr().getNameAsString() : scope.toString();
    }

    private boolean hasConfiguredTimeout(MethodDeclaration method, TypeDeclaration<?> owner,
                                         Set<String> configNames, String setter) {
        Set<String> configs = variablesOfType(owner, configNames);
        method.getParameters().stream().filter(parameter -> parameter.getType().isClassOrInterfaceType()
                && configNames.contains(parameter.getType().asClassOrInterfaceType().getNameAsString()))
                .forEach(parameter -> configs.add(parameter.getNameAsString()));
        return method.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals(setter)
                && call.getArguments().size() == 1 && usesTimeout(call.getArgument(0), method, configs, 0));
    }

    private boolean usesTimeout(com.github.javaparser.ast.expr.Expression expression, MethodDeclaration method,
                                Set<String> configs, int depth) {
        if (depth > 6) {
            return false;
        }
        if (expression.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("getTimeout")
                && call.getScope().map(scope -> configs.contains(receiverName(scope))).orElse(false))) {
            return true;
        }
        Set<String> names = expression.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .map(name -> name.getNameAsString()).collect(Collectors.toSet());
        return method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .anyMatch(variable -> names.contains(variable.getNameAsString()) && variable.getInitializer().isPresent()
                        && usesTimeout(variable.getInitializer().get(), method, configs, depth + 1));
    }

    private void validateApiProperties(TypeDeclaration<?> config, Map<String, String> configs, List<String> errors) {
        com.github.javaparser.ast.expr.AnnotationExpr binding = config.getAnnotations().stream()
                .filter(annotation -> annotation.getName().getIdentifier().equals("ConfigurationProperties"))
                .findFirst().orElse(null);
        String prefix = null;
        if (binding != null && binding.isSingleMemberAnnotationExpr()
                && binding.asSingleMemberAnnotationExpr().getMemberValue().isStringLiteralExpr()) {
            prefix = binding.asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().asString();
        } else if (binding != null && binding.isNormalAnnotationExpr()) {
            prefix = binding.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> Arrays.asList("prefix", "value").contains(pair.getNameAsString())
                            && pair.getValue().isStringLiteralExpr())
                    .map(pair -> pair.getValue().asStringLiteralExpr().asString()).findFirst().orElse(null);
        }
        if (prefix == null || prefix.trim().isEmpty()) {
            errors.add("external-api 必须先确认 " + config.getNameAsString() + " 的真实 ConfigurationProperties 前缀");
            return;
        }
        Map<String, Properties> documents = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            if (!new File(entry.getKey()).getName().matches("application(?:-[^.]+)?\\.ya?ml")) {
                continue;
            }
            try {
                YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
                factory.setResources(new ByteArrayResource(entry.getValue().getBytes(StandardCharsets.UTF_8)));
                Properties properties = factory.getObject();
                if (properties != null) {
                    documents.put(entry.getKey(), properties);
                }
            } catch (RuntimeException exception) {
                errors.add(entry.getKey() + " YAML 解析失败: " + exception.getMessage());
            }
        }
        for (Map.Entry<String, Properties> base : documents.entrySet()) {
            if (!new File(base.getKey()).getName().matches("application\\.ya?ml")) {
                continue;
            }
            Properties effective = new Properties();
            effective.putAll(base.getValue());
            Set<String> active = profileNames(base.getValue(), "spring.profiles.include");
            active.addAll(profileNames(base.getValue(), "spring.profiles.active"));
            if (active.isEmpty()) {
                active.addAll(profileNames(base.getValue(), "spring.profiles.default"));
                if (active.isEmpty()) {
                    active.add("default");
                }
            }
            boolean missing = false;
            File directory = new File(base.getKey()).getParentFile();
            for (String profile : active) {
                if (!profile.matches("[\\w-]+")) {
                    errors.add("external-api 目标 profile 为动态表达式，先确认实际激活配置，不能猜测: " + profile);
                    missing = true;
                    continue;
                }
                for (String extension : Arrays.asList("yml", "yaml")) {
                    String path = new File(directory, "application-" + profile + "." + extension).getPath().replace('\\', '/');
                    Properties overlay = documents.get(path);
                    if (overlay != null) {
                        effective.putAll(overlay);
                    } else if (workspaceService.resolve(path).isFile()) {
                        errors.add("external-api 必须先读取明确激活的配置文件: " + path);
                        missing = true;
                    }
                }
            }
            if (!missing && hasProperty(effective, prefix + ".url") && hasProperty(effective, prefix + ".method")
                    && hasProperty(effective, prefix + ".timeout")) {
                return;
            }
        }
        errors.add("external-api 必须读取/补齐基础 application.yml/yaml 与明确激活 profile 合并后的 "
                + prefix + ".url/method/timeout；部署时激活 profile 未知时先明确配置，不能混合互斥 profile");
    }

    private Set<String> profileNames(Properties properties, String key) {
        Set<String> names = new LinkedHashSet<>();
        List<String> values = new ArrayList<>();
        if (properties.getProperty(key) != null) {
            values.add(properties.getProperty(key));
        }
        for (int index = 0; properties.getProperty(key + "[" + index + "]") != null; index++) {
            values.add(properties.getProperty(key + "[" + index + "]"));
        }
        for (String value : values) {
            Arrays.stream(value.split(",")).map(String::trim).filter(name -> !name.isEmpty()).forEach(names::add);
        }
        return names;
    }

    private boolean hasProperty(Properties properties, String key) {
        return properties.getProperty(key) != null && !properties.getProperty(key).trim().isEmpty();
    }

    private boolean knownReturnType(String name, Map<String, CompilationUnit> units) {
        return !Arrays.asList("void", "Object", "String", "Map", "JsonObject").contains(name)
                && !name.contains("<") && units.values().stream().flatMap(unit -> unit.getTypes().stream())
                .anyMatch(type -> name.equals(type.getNameAsString()) || name.endsWith("." + type.getNameAsString()));
    }
}
