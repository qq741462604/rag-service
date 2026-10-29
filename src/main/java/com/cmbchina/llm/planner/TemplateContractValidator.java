package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.workspace.WorkspaceService;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检查预测接口各模板对应的实际落盘结构，避免单次写入被误判为整个 S1 完成。
 * 仅检查本任务已成功读取或写入的文件，不扫描目标工程目录。
 *
 * @author summer
 * @date 2026-10-20 00:00:00
 */
@Component
@RequiredArgsConstructor
public class TemplateContractValidator {

    private final WorkspaceService workspaceService;
    private final MavenDependencySupport dependencySupport;

    public List<String> validate(String template, AgentContext context) {
        List<String> errors = new ArrayList<>();
        if (!isImplementationTemplate(template)) {
            return errors;
        }
        Map<String, String> files = observedFiles(context, errors);
        validatePackages(files, context, errors);
        errors.addAll(dependencySupport.validate(context, files.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".java"))
                .map(Map.Entry::getValue).collect(Collectors.toList())));
        if (template.contains("01-request-entity")) {
            CompilationUnit body = javaFile(files, "AhiHttpBodyEntity.java", errors);
            requireFields(body, errors, "requestHeader", "requestData");
            CompilationUnit header = javaFile(files, "AhiHttpBodyHeaderEntity.java", errors);
            requireFields(header, errors, "entityId", "businessNo", "requestTime", "isTestEvent");
            if (header != null) {
                header.findAll(FieldDeclaration.class).forEach(field -> field.getVariables().stream()
                        .filter(variable -> "isTestEvent".equals(variable.getNameAsString()))
                        .filter(variable -> field.isStatic() || !Arrays.asList("Boolean", "java.lang.Boolean")
                                .contains(variable.getType().asString()))
                        .forEach(variable -> errors.add("AhiHttpBodyHeaderEntity.isTestEvent 必须为实例字段 Boolean，"
                                + "与 setIsTestEvent(false) 保持一致，禁止 String 或 boolean")));
            }
            CompilationUnit data = javaFile(files, "AhiHttpBodyDataEntity.java", errors);
            for (CompilationUnit unit : Arrays.asList(body, header, data)) {
                if (unit == null) {
                    continue;
                }
                for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
                    if (!field.isStatic() && !field.getAnnotationByName("JsonProperty").isPresent()) {
                        errors.add("请求实体字段必须保留 @JsonProperty: " + field.getVariables());
                    }
                }
            }
            if (data != null && data.findAll(FieldDeclaration.class).isEmpty()) {
                errors.add("AhiHttpBodyDataEntity 不能是空实体，必须按接口字段映射生成");
            }
            validateDataMappings(data, context, errors);
        } else if (template.contains("02-config-class")) {
            CompilationUnit config = javaFile(files, "ReadConfig.java", errors);
            requireFields(config, errors, "ahiHttpInvokeEnable", "ahiHttpBusinessUrl",
                    "httpConnectionTimeout", "httpSocketTimeout", "httpMaxtotalConnections",
                    "httpMaxperrouteConnections", "httpConnectionrequestTimeout",
                    "httpConnectionMaxidletime", "httpRequestSentRetryEnabled", "httpRetryTimes");
            if (config != null && (!config.toString().contains("@Configuration")
                    || !config.toString().contains("${http.invoke.url}"))) {
                errors.add("ReadConfig 必须注册为配置 Bean，并从 http.invoke 配置读取 URL/超时");
            }
            validateConfiguration(files, errors);
        } else if (template.contains("03-http-client-util")) {
            errors.addAll(validateHttpClientSource(files.get("HttpClientUtil.java")));
        } else if (template.contains("04-invoke-handler")) {
            CompilationUnit handler = javaFile(files, "AhiHttpInvokerHandler.java", errors);
            requireMethods(handler, errors, "doPost", "buildRequestBody", "getUUID", "getDdApdatestr");
            requireCalls(handler, errors, "getAhiHttpBusinessUrl", "invoke", "buildRequestBody",
                    "writeValueAsString", "getStatusCode", "getAsJsonObject");
            requireText(handler, errors, "AhiHttpBodyEntity", "AhiHttpBodyHeaderEntity",
                    "AhiHttpBodyDataEntity", "response_header", "rst_code");
            validateEntitySetters(handler, files, errors);
            if (handler != null && handler.findAll(StringLiteralExpr.class).stream()
                    .anyMatch(value -> value.asString().matches("https?://.*"))) {
                errors.add("AhiHttpInvokerHandler 的 URL 必须来自 ReadConfig，禁止硬编码");
            }
        } else if (template.contains("05-entry-and-mapping")) {
            String target = targetFileName(context);
            CompilationUnit entry = javaFile(files, target, errors);
            requireMethods(entry, errors, "handle", "ahiInvoke", "verifyInvokeCondition");
            if (entry != null) {
                MethodDeclaration invoke = method(entry, "ahiInvoke");
                if (invoke != null) {
                    String code = invoke.toString();
                    requireText(invoke, errors, "doPost(ctx, eventFields, slfEqpAdr)",
                            "response_header", "response_data", "rst_code", "predict_rescale_score",
                            "verifyInvokeCondition");
                    validateInvokerReference(entry, invoke, files, errors);
                    if (invoke.getParameters().size() != 2
                            || !"void".equals(invoke.getType().asString())
                            || !"PipelineContext ctx".equals(invoke.getParameter(0).toString())
                            || !"EventData eventData".equals(invoke.getParameter(1).toString())) {
                        errors.add("ahiInvoke 必须保留 void ahiInvoke(PipelineContext ctx, EventData eventData) 签名");
                    }
                    List<String> writtenKeys = invoke.findAll(MethodCallExpr.class).stream()
                            .filter(call -> "putDataAndOriginData".equals(call.getNameAsString()))
                            .filter(call -> !call.getArguments().isEmpty() && call.getArgument(0).isStringLiteralExpr())
                            .map(call -> call.getArgument(0).asStringLiteralExpr().asString())
                            .collect(Collectors.toList());
                    if (!writtenKeys.containsAll(Arrays.asList("ahiResultAvailable", "time", "rst_code",
                            "predict_rescale_score", "ahiCostTime"))) {
                        errors.add("ahiInvoke 必须通过 putDataAndOriginData 写回模板要求的五个字段");
                    }
                    if (code.contains("eventFields.put(") || code.contains("eventData.set(")) {
                        errors.add("ahiInvoke 禁止用 eventFields.put/eventData.set 写回结果");
                    }
                }
                MethodDeclaration handle = method(entry, "handle");
                if (handle != null && handle.findAll(com.github.javaparser.ast.stmt.TryStmt.class).stream()
                        .noneMatch(block -> !block.getCatchClauses().isEmpty()
                                && block.getTryBlock().findAll(MethodCallExpr.class).stream()
                                .anyMatch(call -> "ahiInvoke".equals(call.getNameAsString())))) {
                    errors.add("handle 必须在 try-catch 中调用 ahiInvoke，禁止直接内联 HTTP 请求");
                }
            }
        }
        return errors;
    }

    /** S1 完成检查和 S2 修复预演共用，不能只保留调用名而遗漏公共类型与字段。 */
    public List<String> validateHttpClientSource(String source) {
        List<String> errors = new ArrayList<>();
        CompilationUnit client = javaFile(Collections.singletonMap("HttpClientUtil.java", source),
                "HttpClientUtil.java", errors);
        requireMethods(client, errors, "invoke", "invoke0", "init");
        requireCalls(client, errors, "setSocketTimeout", "setConnectTimeout",
                "setConnectionRequestTimeout", "setConnectionManager", "setRetryHandler",
                "startTracing", "inject", "finishTracing", "execute", "handleResponse");
        if (client == null) {
            return errors;
        }
        if (!client.getClassByName("HttpClientUtil").isPresent()) {
            errors.add("HttpClientUtil 必须声明真实工具类，不能以接口或其它类型冒充模板产物");
            return errors;
        }
        String helperType = "com.cmb.bee.tracer.util.BeeTraceClientHelper";
        String adapterType = "com.cmb.bee.tracer.codec.HttpClientInjectAdapter";
        boolean helperDeclared = client.getClassByName("HttpClientUtil").get().getFields().stream()
                .filter(FieldDeclaration::isStatic)
                .flatMap(field -> field.getVariables().stream())
                .filter(variable -> "beeTraceClientHelper".equals(variable.getNameAsString())
                        && isPublicType(client, variable.getType().asString(), helperType))
                .filter(variable -> variable.getInitializer().isPresent()
                        && variable.getInitializer().get().isMethodCallExpr())
                .map(variable -> variable.getInitializer().get().asMethodCallExpr())
                .anyMatch(call -> "getBean".equals(call.getNameAsString())
                        && call.getArguments().size() == 1 && call.getArgument(0).isClassExpr()
                        && isPublicType(client, call.getArgument(0).asClassExpr().getType().asString(), helperType)
                        && call.getScope().isPresent() && call.getScope().get().isMethodCallExpr()
                        && "SpringContextBeanUtil.getApplicationContext()"
                        .equals(call.getScope().get().toString()));
        if (!helperDeclared) {
            errors.add("HttpClientUtil 必须声明静态字段 beeTraceClientHelper，类型为 " + helperType
                    + "，通过 SpringContextBeanUtil.getApplicationContext().getBean(BeeTraceClientHelper.class)"
                    + " 获取真实 Bean；不能只添加 startTracing/inject/finishTracing 调用。"
                    + "请补齐真实 import 和字段，禁止自建同名辅助类或改用 GlobalTracer/getInstance。");
        }
        boolean adapterBound = client.findAll(MethodCallExpr.class).stream()
                .filter(call -> "inject".equals(call.getNameAsString()) && call.getScope().isPresent()
                        && ("beeTraceClientHelper".equals(call.getScope().get().toString())
                        || "HttpClientUtil.beeTraceClientHelper".equals(call.getScope().get().toString())))
                .filter(call -> call.getArguments().size() == 3 && call.getArgument(2).isObjectCreationExpr())
                .anyMatch(call -> isPublicType(client,
                        call.getArgument(2).asObjectCreationExpr().getType().asString(), adapterType));
        if (!adapterBound) {
            errors.add("HttpClientUtil 必须引用真实公共适配器 " + adapterType
                    + " 并创建实例；补齐正确 import，不得在业务包或内部类中仿造适配器。");
        }
        for (String name : Arrays.asList("startTracing", "inject", "finishTracing")) {
            boolean bound = client.findAll(MethodCallExpr.class).stream()
                    .filter(call -> name.equals(call.getNameAsString()) && call.getScope().isPresent())
                    .anyMatch(call -> "beeTraceClientHelper".equals(call.getScope().get().toString())
                            || "HttpClientUtil.beeTraceClientHelper".equals(call.getScope().get().toString()));
            if (!bound) {
                errors.add("模板追踪调用必须通过 beeTraceClientHelper." + name + "，不得用同名方法替代");
            }
        }
        return errors;
    }

    private boolean isPublicType(CompilationUnit unit, String actualType, String qualifiedName) {
        if (qualifiedName.equals(actualType)) {
            return true;
        }
        int separator = qualifiedName.lastIndexOf('.');
        String simpleName = qualifiedName.substring(separator + 1);
        if (!simpleName.equals(actualType) || unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(declaration -> simpleName.equals(declaration.getNameAsString()))) {
            return false;
        }
        if (unit.getImports().stream().anyMatch(declaration -> !declaration.isStatic()
                && !declaration.isAsterisk() && declaration.getNameAsString().endsWith("." + simpleName)
                && !qualifiedName.equals(declaration.getNameAsString()))) {
            return false;
        }
        return unit.getImports().stream().anyMatch(declaration -> !declaration.isStatic()
                && (qualifiedName.equals(declaration.getNameAsString())
                || declaration.isAsterisk() && qualifiedName.substring(0, separator)
                .equals(declaration.getNameAsString())));
    }

    public boolean isImplementationTemplate(String path) {
        return path != null && path.replace('\\', '/').contains("predict-call-template-parts/")
                && !path.contains("00-overview");
    }

    private void validateInvokerReference(CompilationUnit entry, MethodDeclaration invoke,
                                          Map<String, String> files, List<String> errors) {
        String source = files.get("AhiHttpInvokerHandler.java");
        java.util.regex.Matcher pkg = java.util.regex.Pattern.compile(
                "(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source == null ? "" : source);
        if (!pkg.find()) {
            errors.add("入口必须复用已完成的 AhiHttpInvokerHandler，请先读取真实调用类");
            return;
        }
        String packageName = pkg.group(1);
        String qualifiedName = packageName + ".AhiHttpInvokerHandler";
        boolean imported = entry.getImports().stream().anyMatch(declaration -> !declaration.isStatic()
                && (qualifiedName.equals(declaration.getNameAsString())
                || declaration.isAsterisk() && packageName.equals(declaration.getNameAsString())));
        boolean samePackage = entry.getPackageDeclaration()
                .map(declaration -> packageName.equals(declaration.getNameAsString())).orElse(false);
        boolean valid = invoke.findAll(MethodCallExpr.class).stream()
                .filter(call -> "doPost".equals(call.getNameAsString()) && call.getScope().isPresent())
                .map(call -> call.getScope().get())
                .filter(scope -> scope instanceof ObjectCreationExpr)
                .map(scope -> (ObjectCreationExpr) scope)
                .anyMatch(creation -> creation.getArguments().isEmpty()
                        && (qualifiedName.equals(creation.getType().asString())
                        || "AhiHttpInvokerHandler".equals(creation.getType().asString())
                        && (imported || samePackage)));
        if (!valid) {
            errors.add("ahiInvoke 必须通过 new " + qualifiedName
                    + "().doPost 调用真实实现，可使用正确 import，禁止猜测其它包名");
        }
    }

    public String targetFileName(AgentContext context) {
        return context.getSkills().stream()
                .filter(skill -> skill.getTarget() != null && skill.getTarget().getClassName() != null)
                .map(skill -> skill.getTarget().getClassName() + ".java")
                .findFirst().orElse("DataPrepEventHandler.java");
    }

    private Map<String, String> observedFiles(AgentContext context, List<String> errors) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess()) {
                continue;
            }
            String path = null;
            if (observation.getResult() instanceof FileContent) {
                path = ((FileContent) observation.getResult()).getPath();
            } else if (observation.getResult() instanceof FileOperationResult) {
                path = ((FileOperationResult) observation.getResult()).getPath();
            } else if (observation.getInput() instanceof ToolInput
                    && isMutation(observation.getTool())) {
                path = ((ToolInput) observation.getInput()).getPath();
            }
            if (path != null) {
                String name = new File(path).getName();
                if (Arrays.asList("AhiHttpBodyEntity.java", "AhiHttpBodyHeaderEntity.java",
                        "AhiHttpBodyDataEntity.java", "ReadConfig.java", "HttpClientUtil.java",
                        "AhiHttpInvokerHandler.java", "application.yml", "application.yaml",
                        targetFileName(context)).contains(name)) {
                    paths.put(path, name);
                }
            }
        }
        Map<String, String> files = new LinkedHashMap<>();
        Set<String> canonicalPaths = new HashSet<>();
        paths.forEach((path, name) -> {
            try {
                File file = workspaceService.resolve(path);
                // read_file 返回绝对路径，写入返回相对路径，同一文件不能误判为重复类。
                if (!canonicalPaths.add(file.getCanonicalPath())) {
                    return;
                }
                if (!file.isFile() || file.length() > 256 * 1024) {
                    errors.add("模板产物不存在或超过单文件检查上限 256KB: " + path);
                } else {
                    if (files.containsKey(name)) {
                        errors.add("模板产物存在多个同名路径，请先明确复用位置: " + name);
                    }
                    files.put(name, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                errors.add("模板产物读取失败: " + path + ": " + e.getMessage());
            }
        });
        return files;
    }

    private CompilationUnit javaFile(Map<String, String> files, String name, List<String> errors) {
        String source = files.get(name);
        if (source == null) {
            errors.add("当前模板尚缺产物（需成功写入，或读取已有实现确认复用）: " + name);
            return null;
        }
        com.github.javaparser.ParseResult<CompilationUnit> result = new JavaParser(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_11)).parse(source);
        if (!result.isSuccessful() || !result.getResult().isPresent()) {
            errors.add(name + " 不符合 Java 11 语法: " + result.getProblems());
            return null;
        }
        CompilationUnit unit = result.getResult().get();
        if (!unit.getPackageDeclaration().isPresent() || unit.getTypes().isEmpty()
                || !unit.getType(0).getNameAsString().equals(name.replace(".java", ""))) {
            errors.add(name + " 必须包含真实 package 和同名类声明");
        }
        return unit;
    }

    private void validatePackages(Map<String, String> files, AgentContext context, List<String> errors) {
        String targetName = targetFileName(context);
        String targetSource = files.get(targetName);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
        java.util.regex.Matcher targetPackage = pattern.matcher(targetSource == null ? "" : targetSource);
        if (!targetPackage.find()) {
            errors.add("必须先读取真实入口的 package，再生成模板支持类");
            return;
        }
        String basePackage = targetPackage.group(1).replaceAll("\\.extension$", "");
        files.forEach((name, source) -> {
            if (!name.endsWith(".java") || name.equals(targetName)) {
                return;
            }
            java.util.regex.Matcher pkg = pattern.matcher(source);
            if (!pkg.find() || !(pkg.group(1).equals(basePackage) || pkg.group(1).startsWith(basePackage + "."))) {
                errors.add(name + " 必须位于真实业务根包 " + basePackage + " 下，不能照抄模板示例包名");
            }
        });
    }

    private void requireFields(CompilationUnit unit, List<String> errors, String... names) {
        if (unit == null) {
            return;
        }
        List<String> fields = unit.findAll(FieldDeclaration.class).stream()
                .flatMap(field -> field.getVariables().stream()).map(field -> field.getNameAsString())
                .collect(Collectors.toList());
        for (String name : names) {
            if (!fields.contains(name)) {
                errors.add("模板产物缺少字段: " + name);
            }
        }
    }

    private void requireMethods(CompilationUnit unit, List<String> errors, String... names) {
        if (unit != null) {
            for (String name : names) {
                if (method(unit, name) == null || !method(unit, name).getBody().isPresent()
                        || method(unit, name).getBody().get().getStatements().isEmpty()) {
                    errors.add("模板产物缺少非空方法实现: " + name);
                }
            }
        }
    }

    private MethodDeclaration method(CompilationUnit unit, String name) {
        return unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> name.equals(method.getNameAsString())).findFirst().orElse(null);
    }

    private void requireCalls(CompilationUnit unit, List<String> errors, String... names) {
        if (unit != null) {
            List<String> calls = unit.findAll(MethodCallExpr.class).stream()
                    .map(call -> call.getNameAsString()).collect(Collectors.toList());
            for (String name : names) {
                if (!calls.contains(name)) {
                    errors.add("模板固定调用缺失: " + name);
                }
            }
        }
    }

    private void requireText(com.github.javaparser.ast.Node node, List<String> errors, String... texts) {
        if (node != null) {
            String source = node.toString().replaceAll("\\s+", "");
            for (String text : texts) {
                if (!source.contains(text.replaceAll("\\s+", ""))) {
                    errors.add("模板固定结构缺失: " + text);
                }
            }
        }
    }

    private void validateConfiguration(Map<String, String> files, List<String> errors) {
        String yaml = files.getOrDefault("application.yml", files.get("application.yaml"));
        if (yaml == null) {
            errors.add("当前模板尚缺 application.yml 配置的成功读取或写入");
            return;
        }
        try {
            YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            factory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
            Properties properties = factory.getObject();
            for (String key : Arrays.asList("http.invoke.enable", "http.invoke.url",
                    "http.invoke.connection-timeout", "http.invoke.socket-timeout", "http.maxtotal-connections",
                    "http.maxperroute-connections", "http.connectionrequest-timeout", "http.connection-maxidletime",
                    "http.retry.enabled", "http.retry.times")) {
                if (properties == null || properties.getProperty(key, "").trim().isEmpty()) {
                    errors.add("配置模板缺少有效配置项: " + key);
                }
            }
        } catch (Exception e) {
            errors.add("application.yml 配置解析失败: " + e.getMessage());
        }
    }

    /** 根据真实实体字段或显式 setter 核对 Handler，避免靠新增错误协议字段迁就拼写错误。 */
    private void validateEntitySetters(CompilationUnit handler, Map<String, String> files, List<String> errors) {
        if (handler == null) {
            return;
        }
        Map<String, Set<String>> settersByType = new LinkedHashMap<>();
        for (String name : Arrays.asList("AhiHttpBodyEntity", "AhiHttpBodyHeaderEntity", "AhiHttpBodyDataEntity")) {
            CompilationUnit entity = javaFile(files, name + ".java", errors);
            if (entity == null || !entity.getClassByName(name).isPresent()) {
                continue;
            }
            ClassOrInterfaceDeclaration declaration = entity.getClassByName(name).get();
            Set<String> setters = new HashSet<>();
            declaration.getMethods().stream().filter(method -> !method.isStatic()
                    && method.getParameters().size() == 1).forEach(method -> setters.add(method.getNameAsString()));
            boolean classSetter = declaration.getAnnotationByName("Data").isPresent()
                    || declaration.getAnnotationByName("Setter").isPresent();
            boolean classSetterDisabled = declaration.getAnnotationByName("Setter")
                    .map(annotation -> annotation.toString().contains("NONE")).orElse(false);
            for (FieldDeclaration field : declaration.getFields()) {
                boolean fieldSetter = field.getAnnotationByName("Setter").isPresent();
                boolean disabled = field.getAnnotationByName("Setter")
                        .map(annotation -> annotation.toString().contains("NONE")).orElse(classSetterDisabled);
                if (!field.isStatic() && !field.isFinal() && !disabled && (classSetter || fieldSetter)) {
                    field.getVariables().forEach(variable -> {
                        String fieldName = variable.getNameAsString();
                        setters.add("set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1));
                    });
                }
            }
            settersByType.put(name, setters);
        }
        Map<String, String> variableTypes = new LinkedHashMap<>();
        handler.findAll(VariableDeclarator.class).forEach(variable -> {
            String type = variable.getType().asString();
            String simpleType = type.substring(type.lastIndexOf('.') + 1);
            if (settersByType.containsKey(simpleType)) {
                variableTypes.put(variable.getNameAsString(), simpleType);
            }
        });
        handler.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().startsWith("set") && call.getArguments().size() == 1
                        && call.getScope().isPresent() && call.getScope().get().isNameExpr())
                .forEach(call -> {
                    String type = variableTypes.get(call.getScope().get().asNameExpr().getNameAsString());
                    if (type != null && !settersByType.get(type).contains(call.getNameAsString())) {
                        errors.add("AhiHttpInvokerHandler 调用 " + type + "." + call.getNameAsString()
                                + " 与真实实体不一致；可用 setter: " + settersByType.get(type)
                                + "。先读取实体和调用方，按已有 @JsonProperty 对齐调用；"
                                + "SLF_EQP_ADR 不得改成或新增为 SLF_REQ_ADR，不要新增协议字段迁就方法拼写。");
                    }
                });
    }

    private void validateDataMappings(CompilationUnit data, AgentContext context, List<String> errors) {
        if (data == null) {
            return;
        }
        for (FieldDeclaration field : data.findAll(FieldDeclaration.class)) {
            boolean addressField = field.getVariables().stream().anyMatch(variable ->
                    Arrays.asList("slfReqAdr", "slfEqpAdr").contains(variable.getNameAsString()));
            String property = jsonProperty(field);
            if ((addressField && !"SLF_EQP_ADR".equals(property)) || "SLF_REQ_ADR".equals(property)) {
                errors.add("AhiHttpBodyDataEntity 自助设备地址字段必须映射 SLF_EQP_ADR，"
                        + "禁止为适配错误 setter 新增 SLF_REQ_ADR；应修改 AhiHttpInvokerHandler 的调用");
            }
        }
        long addressMappings = data.findAll(FieldDeclaration.class).stream()
                .filter(field -> !field.isStatic() && "SLF_EQP_ADR".equals(jsonProperty(field)))
                .mapToLong(field -> field.getVariables().size()).sum();
        if (addressMappings > 1) {
            errors.add("SLF_EQP_ADR 只能映射一个实体字段，禁止新增重复字段来兼容错误 setter；应修改调用方");
        }
        // 示例字段允许按需求裁剪；保留下来的同名字段不允许改写协议大小写。
        for (Observation observation : context.getObservations()) {
            if (!(observation.getResult() instanceof FileContent) || !observation.isSuccess()) {
                continue;
            }
            FileContent content = (FileContent) observation.getResult();
            if (content.getPath() == null || !content.getPath().endsWith("01-request-entity.md")) {
                continue;
            }
            String template = String.join("\n", content.getLines()).replaceAll("(?m)^\\s*\\d+\\s*[:|]\\s?", "");
            java.util.regex.Matcher mappings = java.util.regex.Pattern.compile(
                    "@JsonProperty\\(\"([^\"]+)\"\\)\\s+private\\s+\\w+\\s+(\\w+)").matcher(template);
            while (mappings.find()) {
                String property = mappings.group(1);
                String fieldName = mappings.group(2);
                data.findAll(FieldDeclaration.class).stream()
                        .filter(field -> field.getVariables().stream()
                                .anyMatch(variable -> fieldName.equals(variable.getNameAsString())))
                        .filter(field -> !property.equals(jsonProperty(field)))
                        .forEach(field -> errors.add("字段 " + fieldName + " 必须保留协议映射 " + property));
            }
        }
    }

    private String jsonProperty(FieldDeclaration field) {
        return field.getAnnotationByName("JsonProperty").map(annotation -> {
            if (annotation.isSingleMemberAnnotationExpr()
                    && annotation.asSingleMemberAnnotationExpr().getMemberValue().isStringLiteralExpr()) {
                return annotation.asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().asString();
            }
            if (annotation.isNormalAnnotationExpr()) {
                return annotation.asNormalAnnotationExpr().getPairs().stream()
                        .filter(pair -> "value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr())
                        .map(pair -> pair.getValue().asStringLiteralExpr().asString()).findFirst().orElse("");
            }
            return "";
        }).orElse("");
    }

    private boolean isMutation(String tool) {
        return Arrays.asList("write_file", "create_file", "patch_file", "edit_file").contains(tool);
    }
}
