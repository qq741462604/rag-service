package com.cmbchina.llm.verify;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S2 真实测试源码的有界读取及 JUnit 结构门禁，不替代实际测试执行。
 *
 * @author summer
 * @date 2026-10-20 00:00:00
 */
@Component
public class TestSourceInspector {

    public String read(File file) {
        try {
            if (!file.isFile() || Files.size(file.toPath()) > 256 * 1024) {
                throw new IllegalArgumentException("文件不存在或超过 256 KiB: " + file);
            }
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            try (BufferedReader reader = new BufferedReader(new StringReader(source))) {
                int lines = 0;
                while (reader.readLine() != null) {
                    if (++lines > 10000) {
                        throw new IllegalArgumentException("文件超过 S2 单文件 10000 行边界，请拆分任务: " + file);
                    }
                }
            }
            return source;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("读取源码失败: " + file, exception);
        }
    }

    public CompilationUnit parse(String source) {
        // 此处是目标工程源码的语法级别，不是生成器自身的 JDK 基线。
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_11));
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || !result.getResult().isPresent()) {
            // 修复补丁：先集中给出位置和解析原因，再附源码；日志隐藏源码时不再截掉原因。
            String[] lines = source.split("\\R", -1);
            String details = result.getProblems().stream().limit(3).map(problem -> {
                String message = problem.getMessage();
                int stacktrace = message.indexOf("Problem stacktrace");
                if (stacktrace >= 0) {
                    message = message.substring(0, stacktrace);
                }
                message = message.replaceAll("\\s+", " ").trim();
                String location = problem.getLocation().flatMap(range -> range.toRange()).map(range ->
                        "第 " + range.begin.line + " 行，第 " + range.begin.column + " 列，").orElse("");
                return location + "解析原因=" + message.substring(0, Math.min(500, message.length()));
            }).collect(java.util.stream.Collectors.joining(" | "));
            String excerpts = result.getProblems().stream().limit(3).map(problem -> {
                String location = problem.getLocation().flatMap(range -> range.toRange()).map(range -> {
                    int line = range.begin.line;
                    String excerpt = line > 0 && line <= lines.length ? lines[line - 1].trim() : "";
                    return "第 " + line + " 行: " + excerpt.substring(0, Math.min(240, excerpt.length()));
                }).orElse("");
                return location;
            }).collect(java.util.stream.Collectors.joining("\n"));
            throw new IllegalArgumentException("Java 语法解析失败（不是计划 JSON 转换失败，原计划未执行）: "
                    + details + "\n源码: " + excerpts);
        }
        return result.getResult().get();
    }

    public String skipReason(CompilationUnit unit) {
        if (unit.getTypes().isEmpty()) {
            return "没有顶层类型";
        }
        if (unit.getType(0).isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration type = unit.getType(0).asClassOrInterfaceDeclaration();
            if (type.isInterface() || type.isAbstract()) {
                return "接口或抽象类型，不作为独立测试条目";
            }
        }
        if ((unit.getType(0).isEnumDeclaration() || unit.getType(0).isAnnotationDeclaration())
                && unit.getType(0).getMethods().isEmpty()) {
            return "无独立方法的枚举或注解";
        }
        return null;
    }

    public List<String> validate(String source, String sourceClass, String expectedClass) {
        return validate(source, sourceClass, expectedClass, null);
    }

    public List<String> validate(String source, String sourceClass, String expectedClass, String businessSource) {
        return validate(source, sourceClass, expectedClass, businessSource, java.util.Collections.emptyMap());
    }

    public List<String> validate(String source, String sourceClass, String expectedClass, String businessSource,
                                 Map<String, String> relatedSources) {
        List<String> errors = new ArrayList<>();
        CompilationUnit unit = parse(source);
        Set<String> cachedConfigurationGetters = new HashSet<>();
        validateReflectionResultTypes(unit, errors);
        if (businessSource != null) {
            CompilationUnit business = parse(businessSource);
            // 修复补丁3：工具类单测不能把被测对象本身替换为默认不执行真实方法的构造器 Mock。
            validateHttpUtilSubjectMock(unit, business, sourceClass, errors);
            validateReflectiveFieldWrites(unit, business, sourceClass, errors);
            validateCachedHttpClientFixture(unit, business, sourceClass, errors);
            validateMutableStaticBeans(unit, business, sourceClass, errors);
            validateRequestDateAssertions(unit, business, errors);
            validatePredictResponseCoverage(unit, business, errors);
            validateSpringInitializationOrder(unit, business, sourceClass, errors);
            validateLombokToStringAssertions(unit, business, errors);
            collectCachedConfigurationGetters(business, cachedConfigurationGetters);
        }
        relatedSources.forEach((owner, content) -> {
            CompilationUnit related = parse(content);
            validateReflectiveFieldWrites(unit, related, owner, errors);
            validateCachedHttpClientFixture(unit, related, owner, errors);
            validateMutableStaticBeans(unit, related, owner, errors);
            validateSpringInitializationOrder(unit, related, owner, errors);
            collectCachedConfigurationGetters(related, cachedConfigurationGetters);
        });
        validateHttpClientMocks(unit, errors);
        validateHttpClientReferences(unit, errors);
        validateHttpRequestEntityAccess(unit, errors);
        validateStringEntityBodyAccess(unit, errors);
        validateHttpExecuteMatchers(unit, errors);
        validateHttpMockLifetime(unit, errors);
        validateHttpPoolConfiguration(unit, cachedConfigurationGetters, errors);
        validateReflectiveHttpInitialization(unit, errors);
        TestLifecycleInspector.validate(unit, errors);
        validateSpringInteractionBoundary(unit, errors);
        unit.findAll(MethodCallExpr.class).stream().filter(call -> isImpossibleBooleanStringAssertion(unit, call))
                .forEach(call -> errors.add("断言将布尔表达式转为字符串后与非布尔字符串比较，必然失败: " + call
                        + "；应验证真实业务结果或 EventData 写回，不能用集合 size 比较凑断言"));
        boolean removesFinal = unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                "getDeclaredField".equals(call.getNameAsString()) && call.getArguments().size() == 1
                        && call.getArgument(0).isStringLiteralExpr()
                        && "modifiers".equals(call.getArgument(0).asStringLiteralExpr().asString()))
                && unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                "setInt".equals(call.getNameAsString()) && call.getArguments().stream().anyMatch(argument ->
                        argument.findAll(com.github.javaparser.ast.expr.UnaryExpr.class).stream().anyMatch(unary ->
                                unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.BITWISE_COMPLEMENT
                                        && unary.getExpression().toString().endsWith("Modifier.FINAL"))));
        if (removesFinal) {
            errors.add("禁止通过 Field.modifiers 去除 FINAL 后改写生产静态缓存；在首次初始化前 Mock 真实公开工厂，"
                    + "只读访问缓存或保存/恢复可变依赖，不得反射写 final");
        }
        String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString() + ".").orElse("");
        if (unit.getTypes().stream().noneMatch(type -> (packageName + type.getNameAsString()).equals(expectedClass))) {
            errors.add("测试包名/顶层类名必须与路径匹配: " + expectedClass);
        }
        if (unit.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream()
                .noneMatch(name -> name.asString().equals(sourceClass))) {
            errors.add("测试必须引用真实业务类: " + sourceClass);
        }
        if (unit.findAll(AnnotationExpr.class).stream().anyMatch(annotation -> isDisabled(unit, annotation))) {
            errors.add("不允许禁用/跳过本次测试");
        }
        if (unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                call.getNameAsString().startsWith("assume")
                        || call.getNameAsString().equals("assumingThat"))) {
            errors.add("不允许通过 assumption 跳过本次测试");
        }
        int tests = 0;
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!isTest(unit, method)) {
                continue;
            }
            tests++;
            if (!method.getBody().isPresent() || method.getBody().get().getStatements().isEmpty()) {
                errors.add("测试方法为空: " + method.getNameAsString());
            } else if (assertionCount(unit, method) == 0) {
                errors.add("测试方法缺少有效断言或交互验证: " + method.getNameAsString());
            } else if (method.findAll(MethodCallExpr.class).stream().filter(call -> isEffectiveAssertion(unit, call))
                    .allMatch(call -> isConstructionNotNull(method, call))) {
                errors.add("测试仅验证直接 new 的对象非空，未验证业务行为: " + method.getNameAsString()
                        + "；保留已有断言，补充初始化后的状态、配置值或真实依赖交互验证，不删除测试。");
            }
            validateScoreAssertion(unit, method, errors);
        }
        if (tests == 0) {
            errors.add("缺少可执行的 JUnit4/5 测试方法，不支持用空壳或其他框架绕过");
        }
        return errors;
    }

    /** 仅检查直接可见的共享初始化顺序，辅助夹具和类级初始化仍由实际执行验证。 */
    private void validateSpringInitializationOrder(CompilationUnit unit, CompilationUnit business,
                                                   String owner, List<String> errors) {
        boolean springFields = business.findAll(FieldDeclaration.class).stream().filter(FieldDeclaration::isStatic)
                .flatMap(field -> field.getVariables().stream()).anyMatch(variable -> variable.getInitializer()
                        .map(value -> value.toString().contains("SpringContextBeanUtil.getApplicationContext()"))
                        .orElse(false));
        if (!springFields || unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(type -> !type.getExtendedTypes().isEmpty())
                || unit.findAll(AnnotationExpr.class).stream().anyMatch(annotation -> java.util.Arrays.asList(
                "BeforeAll", "BeforeClass", "SpringBootTest", "ContextConfiguration")
                .contains(annotation.getName().getIdentifier()))) {
            return;
        }
        String qualified = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("") + owner;
        Set<String> mutableStatics = business.findAll(FieldDeclaration.class).stream()
                .filter(field -> field.isStatic() && !field.isFinal()).flatMap(field -> field.getVariables().stream())
                .map(variable -> variable.getNameAsString()).collect(java.util.stream.Collectors.toSet());
        for (MethodDeclaration setup : unit.findAll(MethodDeclaration.class)) {
            if (setup.getAnnotations().stream().noneMatch(annotation -> java.util.Arrays.asList("BeforeEach", "Before")
                    .contains(annotation.getName().getIdentifier())) || !setup.getBody().isPresent()) {
                continue;
            }
            boolean helper = setup.findAll(MethodCallExpr.class).stream().anyMatch(call -> !call.getScope().isPresent()
                    && unit.findAll(MethodDeclaration.class).stream().anyMatch(method -> method != setup
                    && method.getNameAsString().equals(call.getNameAsString())));
            if (helper) {
                continue;
            }
            int firstUse = Integer.MAX_VALUE;
            int prepared = Integer.MAX_VALUE;
            for (int index = 0; index < setup.getBody().get().getStatements().size(); index++) {
                com.github.javaparser.ast.stmt.Statement statement = setup.getBody().get().getStatement(index);
                if (!statement.isExpressionStmt()) {
                    continue;
                }
                boolean use = statement.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                        .anyMatch(field -> matchesType(unit, field.getScope().toString(), qualified)
                                && mutableStatics.contains(field.getNameAsString()));
                use |= statement.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                        .anyMatch(creation -> matchesType(unit, creation.getType().asString(), qualified));
                use |= statement.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        call.getScope().map(scope -> matchesType(unit, scope.toString(), qualified)).orElse(false));
                if (use) {
                    firstUse = Math.min(firstUse, index);
                }
                boolean contextReady = statement.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        call.getNameAsString().equals("thenReturn") && call.getArguments().size() == 1
                                && !call.getArgument(0).isNullLiteralExpr() && call.getScope().map(scope ->
                                scope.findAll(com.github.javaparser.ast.expr.MethodReferenceExpr.class).stream()
                                        .anyMatch(reference -> reference.getIdentifier().equals("getApplicationContext")
                                                && reference.getScope().toString().endsWith("SpringContextBeanUtil"))
                                || scope.findAll(MethodCallExpr.class).stream().anyMatch(getter ->
                                getter.getNameAsString().equals("getApplicationContext") && getter.getScope()
                                        .map(receiver -> receiver.toString().endsWith("SpringContextBeanUtil")).orElse(false)))
                                .orElse(false));
                if (contextReady) {
                    prepared = Math.min(prepared, index);
                }
            }
            if (firstUse != Integer.MAX_VALUE && prepared != Integer.MAX_VALUE && firstUse < prepared) {
                errors.add("Spring 静态初始化顺序错误: " + owner + " 在 " + setup.getNameAsString()
                        + " 中先被主动使用，之后才准备 getApplicationContext 桩；保存原静态字段也会触发类初始化。"
                        + "先准备配置/追踪 Bean、注册 SpringContextBeanUtil 静态 Mock 并返回有效上下文，再读取或注入业务静态字段；"
                        + "保留状态恢复与 Mock 清理，不改生产 static/final。");
            }
        }
    }

    public boolean requiresInlineMocking(String source) {
        CompilationUnit unit = parse(source);
        return unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                isMockitoCall(unit, call, "mockStatic") || isMockitoCall(unit, call, "mockConstruction")
                        || isMockitoCall(unit, call, "mockConstructionWithAnswer"));
    }

    private boolean isConstructionNotNull(MethodDeclaration method, MethodCallExpr call) {
        return call.getNameAsString().equals("assertNotNull") && call.getArguments().size() == 1
                && call.getArgument(0).isNameExpr()
                && method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream().anyMatch(variable ->
                variable.getNameAsString().equals(call.getArgument(0).asNameExpr().getNameAsString())
                        && variable.getInitializer().filter(Expression::isObjectCreationExpr).isPresent())
                && method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream().noneMatch(assignment ->
                assignment.getTarget().equals(call.getArgument(0)));
    }

    /** 固定评分夹具不能仅验证写回类型；保留原验证，再增加精确值或捕获值断言。 */
    private void validateScoreAssertion(CompilationUnit unit, MethodDeclaration method, List<String> errors) {
        boolean fixedScore = method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                call.getNameAsString().equals("addProperty") && call.getArguments().size() == 2
                        && call.getArgument(0).isStringLiteralExpr()
                        && call.getArgument(0).asStringLiteralExpr().asString().equals("predict_rescale_score")
                        && (call.getArgument(1).isDoubleLiteralExpr() || call.getArgument(1).isIntegerLiteralExpr()));
        if (!fixedScore) {
            return;
        }
        List<MethodCallExpr> checks = method.findAll(MethodCallExpr.class).stream().filter(call ->
                call.getNameAsString().equals("putDataAndOriginData") && call.getArguments().size() == 2
                        && call.getArgument(0).toString().contains("\"predict_rescale_score\"")
                        && call.getScope().filter(Expression::isMethodCallExpr)
                        .map(scope -> isMockitoCall(unit, scope.asMethodCallExpr(), "verify")).orElse(false))
                .collect(java.util.stream.Collectors.toList());
        if (!checks.isEmpty() && checks.stream().allMatch(call -> call.getArgument(1).isMethodCallExpr()
                && call.getArgument(1).asMethodCallExpr().getNameAsString().equals("any")
                && call.getArgument(1).asMethodCallExpr().getArguments().size() == 1
                && call.getArgument(1).asMethodCallExpr().getArgument(0).isClassExpr()
                && matchesType(unit, call.getArgument(1).asMethodCallExpr().getArgument(0)
                .asClassExpr().getType().asString(), "java.math.BigDecimal"))) {
            errors.add("固定评分夹具仅验证 BigDecimal 类型: " + method.getNameAsString()
                    + "；保留已有 verify，再补同一写回的 eq(按真实 Float/BigDecimal 转换构造的期望值)，"
                    + "或增加 ArgumentCaptor 捕获及具体值断言；不得改生产转换或删除原断言。");
        }
    }

    /** 仅在本次编译明确点名缺失 API 后检查，避免把当前依赖版本的缺口泛化到所有工程。 */
    public List<String> validateReportedApiUsage(String source, List<String> verifyErrors) {
        List<String> errors = new ArrayList<>();
        String diagnostics = String.join("\n", verifyErrors);
        if (!diagnostics.contains("com.cmbchina.rces.executor.common.EventData")
                || !diagnostics.contains("setData")
                || !(diagnostics.contains("找不到符号") || diagnostics.contains("cannot find symbol"))) {
            return errors;
        }
        CompilationUnit unit = parse(source);
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("setData") || call.getArguments().size() != 1
                    || !call.getScope().filter(Expression::isNameExpr).isPresent()) {
                continue;
            }
            String name = call.getScope().get().asNameExpr().getNameAsString();
            MethodDeclaration method = call.findAncestor(MethodDeclaration.class).orElse(null);
            if (method == null) {
                continue;
            }
            List<String> types = method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name))
                    .map(variable -> variable.getType().asString()).collect(java.util.stream.Collectors.toList());
            if (types.isEmpty()) {
                method.getParameters().stream().filter(parameter -> parameter.getNameAsString().equals(name))
                        .forEach(parameter -> types.add(parameter.getType().asString()));
            }
            if (types.isEmpty()) {
                method.findAncestor(ClassOrInterfaceDeclaration.class).flatMap(owner -> owner.getFieldByName(name))
                        .ifPresent(field -> field.getVariables().stream()
                                .filter(variable -> variable.getNameAsString().equals(name))
                                .forEach(variable -> types.add(variable.getType().asString())));
            }
            if (types.size() == 1 && matchesType(unit, types.get(0), "com.cmbchina.rces.executor.common.EventData")) {
                errors.add("本次编译已确认 EventData 没有 setData(Map)，候选源码仍残留 "
                        + method.getNameAsString() + ": " + call + "；非空真实实例用 getData().putAll(原Map)，"
                        + "null/特殊集合夹具用真实 getData API 的 Mock 桩，保留全部断言和 null 语义。请修复文件中所有同类调用");
            }
        }
        return errors;
    }

    /** Spring getField 返回 Object，赋给具体类型前必须显式转换；不猜测未知辅助方法的返回类型。 */
    private void validateReflectionResultTypes(CompilationUnit unit, List<String> errors) {
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!isReflectionTestCall(unit, call, "getField") || call.getArguments().size() < 2) {
                continue;
            }
            com.github.javaparser.ast.Node parent = call.getParentNode().orElse(null);
            String type = null;
            if (parent instanceof com.github.javaparser.ast.body.VariableDeclarator) {
                type = ((com.github.javaparser.ast.body.VariableDeclarator) parent).getType().asString();
            } else if (parent instanceof com.github.javaparser.ast.expr.AssignExpr) {
                com.github.javaparser.ast.expr.AssignExpr assignment = (com.github.javaparser.ast.expr.AssignExpr) parent;
                if (assignment.getValue() != call || !assignment.getTarget().isNameExpr()) {
                    continue;
                }
                String name = assignment.getTarget().asNameExpr().getNameAsString();
                MethodDeclaration method = call.findAncestor(MethodDeclaration.class).orElse(null);
                List<com.github.javaparser.ast.body.VariableDeclarator> locals = method == null ? new ArrayList<>()
                        : method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                        .filter(variable -> variable.getNameAsString().equals(name))
                        .collect(java.util.stream.Collectors.toList());
                if (locals.size() == 1) {
                    type = locals.get(0).getType().asString();
                } else if (locals.isEmpty() && method != null && method.getParameters().stream()
                        .noneMatch(parameter -> parameter.getNameAsString().equals(name))) {
                    type = method.findAncestor(ClassOrInterfaceDeclaration.class)
                            .flatMap(owner -> owner.getFieldByName(name))
                            .flatMap(field -> field.getVariables().stream()
                                    .filter(variable -> variable.getNameAsString().equals(name)).findFirst())
                            .map(variable -> variable.getType().asString()).orElse(null);
                }
            }
            if (type != null && !java.util.Arrays.asList("Object", "java.lang.Object", "var").contains(type)) {
                errors.add("ReflectionTestUtils.getField 返回 Object，不能直接赋给 " + type + ": " + call
                        + "；根据真实字段声明显式转换，例如 (" + type + ") " + call
                        + "。此转换只读取缓存，不能随后 setField 写回生产 static final 缓存，即使写回同一个对象也不允许");
            }
        }
    }

    private boolean isReflectionTestCall(CompilationUnit unit, MethodCallExpr call, String name) {
        return call.getNameAsString().equals(name) && (call.getScope().map(scope -> matchesType(unit,
                scope.toString(), "org.springframework.test.util.ReflectionTestUtils")).orElse(false)
                || !call.getScope().isPresent() && unit.getImports().stream().anyMatch(declaration ->
                declaration.isStatic() && (declaration.getNameAsString().equals("org.springframework.test.util.ReflectionTestUtils." + name)
                        || declaration.isAsterisk() && declaration.getNameAsString().equals("org.springframework.test.util.ReflectionTestUtils")))
                && unit.findAll(MethodDeclaration.class).stream().noneMatch(method -> method.getNameAsString().equals(name)));
    }

    /** 每个用例新建工厂 Mock 不会更新生产 static final 中已缓存的客户端。 */
    private void validateCachedHttpClientFixture(CompilationUnit unit, CompilationUnit business,
                                                 String sourceClass, List<String> errors) {
        TypeDeclaration<?> owner = business.getTypes().stream()
                .filter(type -> type.getNameAsString().equals(sourceClass)).findFirst().orElse(null);
        if (owner == null) {
            return;
        }
        FieldDeclaration cache = owner.getFieldByName("httpClientUtil").orElse(null);
        if (cache == null || !cache.isStatic() || !cache.isFinal() || cache.getVariables().size() != 1
                || !cache.getVariable(0).getInitializer().filter(value -> value.isObjectCreationExpr()
                && value.asObjectCreationExpr().getType().getNameAsString().equals("HttpClientUtil")).isPresent()) {
            return;
        }
        boolean invokes = unit.findAll(MethodDeclaration.class).stream().filter(method -> isTest(unit, method))
                .anyMatch(method -> method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> java.util.Arrays.asList("doPost", "handle", "ahiInvoke")
                                .contains(call.getNameAsString()))
                        && method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> call.getNameAsString().equals("execute") && isHttpClientReceiver(unit, method, call)));
        List<MethodCallExpr> fixtureCalls = lifecycleCalls(unit, "BeforeEach", "Before");
        boolean perTestFactory = fixtureCalls.stream().anyMatch(call ->
                        isMockitoCall(unit, call, "mockStatic") && call.getArguments().size() == 1
                                && call.getArgument(0).isClassExpr() && matchesType(unit,
                                call.getArgument(0).asClassExpr().getType().asString(), "org.apache.http.impl.client.HttpClients"))
                        && fixtureCalls.stream().anyMatch(call ->
                        isMockitoCall(unit, call, "mock") && call.getArguments().size() == 1
                                && call.getArgument(0).isClassExpr() && matchesType(unit,
                                call.getArgument(0).asClassExpr().getType().asString(), "org.apache.http.impl.client.CloseableHttpClient"));
        boolean readsCache = unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                call.getNameAsString().equals("getDeclaredField") && call.getArguments().size() == 1
                        && call.getArgument(0).isStringLiteralExpr()
                        && call.getArgument(0).asStringLiteralExpr().asString().equals("httpClientUtil")
                        || call.getNameAsString().equals("getField") && call.getArguments().size() == 2
                        && call.getScope().map(scope -> matchesType(unit, scope.toString(),
                        "org.springframework.test.util.ReflectionTestUtils")).orElse(false)
                        && call.getArgument(1).isStringLiteralExpr()
                        && call.getArgument(1).asStringLiteralExpr().asString().equals("httpClientUtil"));
        if (invokes && perTestFactory && !readsCache) {
            errors.add(sourceClass + ".httpClientUtil 是真实 static final 缓存，但共享 setup 每次创建新的 HTTP Mock，"
                    + "doPost 使用的旧客户端不会自动替换，当前 execute 桩可能从未被调用。"
                    + "首次初始化前准备 Bean/工厂；只读取得该缓存对象，保存并替换其非 final 实例 httpClient 为本轮 Mock，"
                    + "结束后恢复；工具可变静态 Bean 同样保存/注入/恢复。不能对 Class 写实例字段、改 final、另 new 工具代替缓存或删除断言");
        }
        if (invokes && perTestFactory && readsCache) {
            String qualified = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                    + sourceClass;
            List<MethodCallExpr> setup = lifecycleCalls(unit, "BeforeEach", "Before");
            List<MethodCallExpr> cleanup = lifecycleCalls(unit, "AfterEach", "After");
            Set<String> clients = new HashSet<>();
            setup.stream().filter(call -> isMockitoCall(unit, call, "mock") && call.getArguments().size() == 1
                    && call.getArgument(0).isClassExpr() && matchesType(unit,
                    call.getArgument(0).asClassExpr().getType().asString(), "org.apache.http.impl.client.CloseableHttpClient"))
                    .forEach(call -> savedBinding(call).ifPresent(clients::add));
            Set<String> caches = new HashSet<>();
            setup.stream().filter(call -> staticFieldCall(unit, call, "getField", qualified, "httpClientUtil", 2))
                    .forEach(call -> savedBinding(call).ifPresent(caches::add));
            boolean isolated = false;
            for (String receiver : caches) {
                Set<String> saved = new HashSet<>();
                setup.stream().filter(call -> instanceClientCall(unit, call, "getField", receiver, 2))
                        .forEach(call -> savedBinding(call).ifPresent(saved::add));
                boolean injected = setup.stream().anyMatch(call ->
                        instanceClientCall(unit, call, "setField", receiver, 3)
                                && clients.contains(bindingName(call.getArgument(2))));
                boolean restored = cleanup.stream().anyMatch(call ->
                        instanceClientCall(unit, call, "setField", receiver, 3)
                                && saved.contains(bindingName(call.getArgument(2)))
                                && !guardsSavedNull(call, saved));
                isolated |= !saved.isEmpty() && injected && restored;
            }
            if (!isolated) {
                errors.add(sourceClass + ".httpClientUtil 隔离必须落实到缓存实例：BeforeEach 用 ReflectionTestUtils.getField"
                        + "只读获取缓存，保存其 httpClient，再 setField(缓存实例, \"httpClient\", 本轮 CloseableHttpClient Mock)，"
                        + "AfterEach finally 恢复同一旧值；只读取缓存或另 new 工具不算隔离，不允许写 static final");
            }
        }
    }

    private boolean instanceClientCall(CompilationUnit unit, MethodCallExpr call, String operation,
                                        String receiver, int arguments) {
        return isReflectionTestCall(unit, call, operation) && call.getArguments().size() == arguments
                && bindingName(call.getArgument(0)).equals(receiver) && call.getArgument(1).isStringLiteralExpr()
                && call.getArgument(1).asStringLiteralExpr().asString().equals("httpClient");
    }

    /** 只检查真实 Spring 静态 Bean；工厂重新配桩不会改变已初始化的可变静态引用。 */
    private void validateMutableStaticBeans(CompilationUnit unit, CompilationUnit business,
                                            String sourceClass, List<String> errors) {
        List<MethodCallExpr> setup = lifecycleCalls(unit, "BeforeEach", "Before");
        if (setup.stream().noneMatch(call -> isMockitoCall(unit, call, "mockStatic")
                && call.getArguments().size() == 1 && call.getArgument(0).isClassExpr()
                && call.getArgument(0).asClassExpr().getType().asString().endsWith("SpringContextBeanUtil"))) {
            return;
        }
        String owner = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                + sourceClass;
        List<MethodCallExpr> cleanup = lifecycleCalls(unit, "AfterEach", "After");
        for (TypeDeclaration<?> type : business.getTypes()) {
            if (!type.getNameAsString().equals(sourceClass)) {
                continue;
            }
            for (FieldDeclaration field : type.getFields()) {
                if (!field.isStatic() || field.isFinal()) {
                    continue;
                }
                for (com.github.javaparser.ast.body.VariableDeclarator variable : field.getVariables()) {
                    boolean bean = variable.getInitializer().map(value -> value.findAll(MethodCallExpr.class).stream()
                            .anyMatch(call -> call.getNameAsString().equals("getBean")
                                    && call.getArguments().size() == 1 && call.getArgument(0).isClassExpr()
                                    && call.getScope().filter(Expression::isMethodCallExpr).map(scope ->
                                    scope.asMethodCallExpr().getNameAsString().equals("getApplicationContext"))
                                    .orElse(false))).orElse(false);
                    if (!bean) {
                        continue;
                    }
                    String name = variable.getNameAsString();
                    Set<String> saved = new HashSet<>();
                    for (MethodCallExpr read : setup) {
                        if (staticFieldCall(unit, read, "getField", owner, name, 2)) {
                            savedBinding(read).ifPresent(saved::add);
                        }
                    }
                    // public 静态字段也允许直接保存，兼容既有非反射夹具。
                    for (MethodDeclaration method : lifecycleMethods(unit, "BeforeEach", "Before")) {
                        method.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                                .filter(value -> staticFieldReference(unit, value, owner, name))
                                .forEach(value -> savedBinding(value).ifPresent(saved::add));
                    }
                    boolean injected = setup.stream().anyMatch(call ->
                            staticFieldCall(unit, call, "setField", owner, name, 3)
                                    && !call.getArgument(2).isNullLiteralExpr()
                                    && !saved.contains(bindingName(call.getArgument(2))));
                    boolean restored = cleanup.stream().anyMatch(call ->
                            staticFieldCall(unit, call, "setField", owner, name, 3)
                                    && saved.contains(bindingName(call.getArgument(2)))
                                    && !guardsSavedNull(call, saved));
                    injected |= hasStaticAssignment(unit, lifecycleMethods(unit, "BeforeEach", "Before"),
                            owner, name, saved, false);
                    restored |= hasStaticAssignment(unit, lifecycleMethods(unit, "AfterEach", "After"),
                            owner, name, saved, true);
                    if (saved.isEmpty() || !injected || !restored) {
                        errors.add("静态 Bean 隔离不完整: " + sourceClass + "." + name
                                // 修复补丁3：指出缺失环节及字段真正所属者，避免只重复抽象隔离提示。
                                + " [saved=" + !saved.isEmpty() + ", injected=" + injected + ", restored=" + restored + "]"
                                + "；真实字段所属类=" + owner + "，不是 Bean 类型 " + variable.getType().asString()
                                + "；每个 BeforeEach/Before 在 Bean/工厂就绪后保存旧值并注入本轮 Bean，"
                                + "AfterEach/After 的 finally 恢复同一旧值（包括 null）；只换 Spring getBean 桩无效。"
                                + "使用真实字段的直接访问或 ReflectionTestUtils.getField/setField，不改 final/生产初始化，保留全部断言");
                    }
                }
            }
        }
    }

    /** 修复补丁3：仅检查当前 HTTP 工具测试的默认构造器拦截；调用方隔离依赖的 Mock 不受影响。 */
    private void validateHttpUtilSubjectMock(CompilationUnit unit, CompilationUnit business,
                                             String sourceClass, List<String> errors) {
        if (!"HttpClientUtil".equals(sourceClass)) {
            return;
        }
        String owner = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                + sourceClass;
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (isMockitoCall(unit, call, "mockConstruction") && call.getArguments().size() <= 2
                    && !call.getArguments().isEmpty() && call.getArgument(0).isClassExpr()
                    && matchesType(unit, call.getArgument(0).asClassExpr().getType().asString(), owner)
                    && (call.getArguments().size() == 1 || call.getArgument(1).isLambdaExpr()
                    && call.getArgument(1).findAll(MethodCallExpr.class).stream().noneMatch(callback ->
                    java.util.Arrays.asList("callRealMethod", "doCallRealMethod", "thenCallRealMethod")
                            .contains(callback.getNameAsString())))) {
                errors.add("当前被测 HttpClientUtil 被默认构造器 Mock 拦截，invoke 不执行真实业务；"
                        + "使用真实 new HttpClientUtil()，只 Mock 底层 HttpClients/CloseableHttpClient 和追踪依赖。"
                        + "不得从尚无构造的 constructed().get(0) 取对象或人为构造对象凑数量；"
                        + "保留实际请求、追踪及异常断言，不能通过给被测 Mock 配返回值制造通过");
            }
            if (isMockitoCall(unit, call, "mockStatic") && !call.getArguments().isEmpty()
                    && call.getArgument(0).isClassExpr() && matchesType(unit,
                    call.getArgument(0).asClassExpr().getType().asString(), "org.springframework.context.ApplicationContext")) {
                errors.add("ApplicationContext 应为普通实例 Mock，不是静态工具：使用 mock(ApplicationContext.class)，"
                        + "只对 SpringContextBeanUtil 注册可关闭的静态 Mock；删除无用途的 ApplicationContext 静态注册");
            }
        }
    }

    private boolean hasStaticAssignment(CompilationUnit unit, List<MethodDeclaration> methods,
                                         String owner, String field, Set<String> saved, boolean restore) {
        return methods.stream().flatMap(method -> method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream())
                .anyMatch(assignment -> assignment.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
                        && staticFieldReference(unit, assignment.getTarget(), owner, field)
                        && !assignment.getValue().isNullLiteralExpr()
                        && (!restore || !guardsSavedNull(assignment, saved))
                        && saved.contains(bindingName(assignment.getValue())) == restore);
    }

    private boolean guardsSavedNull(com.github.javaparser.ast.Node operation, Set<String> saved) {
        com.github.javaparser.ast.Node node = operation;
        while (node.getParentNode().isPresent() && !(node instanceof MethodDeclaration)) {
            node = node.getParentNode().get();
            if (node instanceof com.github.javaparser.ast.stmt.IfStmt) {
                String condition = ((com.github.javaparser.ast.stmt.IfStmt) node).getCondition().toString();
                if (saved.stream().anyMatch(name -> condition.contains(name + " != null")
                        || condition.contains("null != " + name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean staticFieldReference(CompilationUnit unit, Expression value, String owner, String field) {
        return value.isFieldAccessExpr() && value.asFieldAccessExpr().getNameAsString().equals(field)
                && isHttpClassReference(unit, value.asFieldAccessExpr().getScope(), owner);
    }

    private boolean staticFieldCall(CompilationUnit unit, MethodCallExpr call, String operation,
                                     String owner, String field, int arguments) {
        return isReflectionTestCall(unit, call, operation) && call.getArguments().size() == arguments
                && call.getArgument(0).isClassExpr()
                && matchesType(unit, call.getArgument(0).asClassExpr().getType().asString(), owner)
                && call.getArgument(1).isStringLiteralExpr()
                && call.getArgument(1).asStringLiteralExpr().asString().equals(field);
    }

    private java.util.Optional<String> savedBinding(Expression value) {
        com.github.javaparser.ast.Node node = value;
        while (node.getParentNode().filter(parent -> parent instanceof com.github.javaparser.ast.expr.CastExpr
                || parent instanceof com.github.javaparser.ast.expr.EnclosedExpr).isPresent()) {
            node = node.getParentNode().get();
        }
        com.github.javaparser.ast.Node parent = node.getParentNode().orElse(null);
        if (parent instanceof com.github.javaparser.ast.body.VariableDeclarator) {
            return java.util.Optional.of(((com.github.javaparser.ast.body.VariableDeclarator) parent).getNameAsString());
        }
        if (parent instanceof com.github.javaparser.ast.expr.AssignExpr) {
            com.github.javaparser.ast.expr.AssignExpr assignment = (com.github.javaparser.ast.expr.AssignExpr) parent;
            if (assignment.getValue() == node) {
                return java.util.Optional.of(bindingName(assignment.getTarget()));
            }
        }
        return java.util.Optional.empty();
    }

    private String bindingName(Expression value) {
        return value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr()
                ? value.asFieldAccessExpr().getNameAsString() : value.toString();
    }

    /** 有界跟随本测试类的无参 helper，清理 helper 和测试侧转发子类不再让整项检查失效。 */
    private List<MethodDeclaration> lifecycleMethods(CompilationUnit unit, String... annotations) {
        Set<MethodDeclaration> methods = new java.util.LinkedHashSet<>();
        unit.findAll(MethodDeclaration.class).stream().filter(method -> method.getAnnotations().stream()
                .anyMatch(annotation -> java.util.Arrays.stream(annotations).anyMatch(name ->
                        java.util.Arrays.asList("org.junit.jupiter.api." + name, "org.junit." + name)
                                .contains(qualifiedAnnotation(unit, annotation)))))
                .forEach(methods::add);
        for (int depth = 0; depth < 4; depth++) {
            Set<MethodDeclaration> added = new java.util.LinkedHashSet<>();
            for (MethodDeclaration method : methods) {
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (!call.getArguments().isEmpty() || call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
                        continue;
                    }
                    unit.findAll(MethodDeclaration.class).stream().filter(helper -> helper.getParameters().isEmpty()
                            && helper.getNameAsString().equals(call.getNameAsString())
                            && helper.findAncestor(ClassOrInterfaceDeclaration.class)
                            .equals(method.findAncestor(ClassOrInterfaceDeclaration.class)))
                            .forEach(added::add);
                }
            }
            if (!methods.addAll(added)) {
                break;
            }
        }
        return new ArrayList<>(methods);
    }

    private List<MethodCallExpr> lifecycleCalls(CompilationUnit unit, String... annotations) {
        return lifecycleMethods(unit, annotations).stream().flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                .collect(java.util.stream.Collectors.toList());
    }

    /** 精确次数验证必须先隔离 setup/类初始化的交互，不能以 atLeastOnce 放宽次数。 */
    private void validateSpringInteractionBoundary(CompilationUnit unit, List<String> errors) {
        boolean initializes = lifecycleCalls(unit, "BeforeEach", "Before").stream().anyMatch(call ->
                isReflectionTestCall(unit, call, "getField") && !call.getArguments().isEmpty()
                        && call.getArgument(0).isClassExpr());
        if (!initializes) {
            return;
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!isTest(unit, method)) {
                continue;
            }
            for (MethodCallExpr verify : method.findAll(MethodCallExpr.class)) {
                if (!verify.getNameAsString().equals("verify") || !verify.getScope().filter(Expression::isNameExpr).isPresent()
                        || verify.getArguments().isEmpty() || !verify.getArgument(0).isMethodReferenceExpr()
                        || !verify.getArgument(0).asMethodReferenceExpr().getIdentifier().equals("getApplicationContext")) {
                    continue;
                }
                String handle = verify.getScope().get().asNameExpr().getNameAsString();
                boolean mockHandle = unit.findAll(FieldDeclaration.class).stream().flatMap(field -> field.getVariables().stream())
                        .anyMatch(variable -> variable.getNameAsString().equals(handle)
                                && variable.getType().asString().contains("MockedStatic<")
                                && variable.getType().asString().contains("SpringContextBeanUtil"));
                List<MethodCallExpr> init = method.findAll(MethodCallExpr.class).stream()
                        .filter(call -> call.getNameAsString().equals("init"))
                        .collect(java.util.stream.Collectors.toList());
                if (!mockHandle || init.size() != 1) {
                    continue;
                }
                boolean cleared = method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        call.getNameAsString().equals("clearInvocations") && call.getArguments().isEmpty()
                                && call.getScope().map(scope -> scope.toString().equals(handle)).orElse(false)
                                && immediatelyBefore(call, init.get(0)));
                if (!cleared) {
                    errors.add("初始化交互边界不清: " + method.getNameAsString() + "；在真实 init 调用前执行 "
                            + handle + ".clearInvocations()，保留原 getApplicationContext 精确次数验证；"
                            + "不要 reset（会删除桩）、更改次数或修改生产初始化");
                }
            }
        }
    }

    private boolean immediatelyBefore(MethodCallExpr clear, MethodCallExpr init) {
        com.github.javaparser.ast.Node first = clear.getParentNode().orElse(null);
        com.github.javaparser.ast.Node second = init.getParentNode().orElse(null);
        if (!(first instanceof com.github.javaparser.ast.stmt.ExpressionStmt)
                || !(second instanceof com.github.javaparser.ast.stmt.ExpressionStmt)
                || !first.getParentNode().equals(second.getParentNode())
                || !first.getParentNode().filter(parent -> parent instanceof com.github.javaparser.ast.stmt.BlockStmt).isPresent()) {
            return false;
        }
        com.github.javaparser.ast.stmt.BlockStmt block = (com.github.javaparser.ast.stmt.BlockStmt) first.getParentNode().get();
        int position = block.getStatements().indexOf(first);
        return position >= 0 && position + 1 < block.getStatements().size()
                && block.getStatement(position + 1) == second;
    }

    /** 只依据真实被测类声明及方法内直接绑定，预检已确认的反射写入错误。 */
    private void validateReflectiveFieldWrites(CompilationUnit unit, CompilationUnit business,
                                               String sourceClass, List<String> errors) {
        TypeDeclaration<?> owner = business.getTypes().stream()
                .filter(type -> type.getNameAsString().equals(sourceClass)).findFirst().orElse(null);
        if (owner == null) {
            return;
        }
        String qualified = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                + sourceClass;
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (isReflectionTestCall(unit, call, "setField") && call.getArguments().size() == 3
                    && isBusinessClass(unit, call.getArgument(0), qualified, java.util.Collections.emptySet())
                    && call.getArgument(1).isStringLiteralExpr()) {
                owner.getFieldByName(call.getArgument(1).asStringLiteralExpr().asString()).ifPresent(field ->
                        validateReflectiveWrite(field, true, qualified, call, errors));
            }
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!method.getBody().isPresent()) {
                continue;
            }
            Set<String> classAliases = new HashSet<>();
            Map<String, FieldDeclaration> fields = new LinkedHashMap<>();
            for (com.github.javaparser.ast.stmt.Statement statement : method.getBody().get().getStatements()) {
                // 分支及辅助方法中的动态赋值不猜测；丢弃旧绑定，避免将过期别名当成确定证据。
                if (!statement.isExpressionStmt()) {
                    classAliases.clear();
                    fields.clear();
                    continue;
                }
                Expression expression = statement.asExpressionStmt().getExpression();
                if (expression.isVariableDeclarationExpr()) {
                    expression.asVariableDeclarationExpr().getVariables().forEach(variable ->
                            bindReflectiveField(unit, owner, qualified, variable.getNameAsString(),
                                    variable.getInitializer().orElse(null), classAliases, fields));
                } else if (expression.isAssignExpr()) {
                    com.github.javaparser.ast.expr.AssignExpr assignment = expression.asAssignExpr();
                    String target = assignment.getTarget().isNameExpr()
                            ? assignment.getTarget().asNameExpr().getNameAsString() : null;
                    if (target != null) {
                        bindReflectiveField(unit, owner, qualified, target,
                                assignment.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
                                        ? assignment.getValue() : null, classAliases, fields);
                    }
                } else if (expression.isMethodCallExpr()) {
                    MethodCallExpr call = expression.asMethodCallExpr();
                    if (call.getNameAsString().equals("set") && call.getArguments().size() == 2
                            && call.getScope().filter(Expression::isNameExpr).isPresent()) {
                        FieldDeclaration field = fields.get(call.getScope().get().asNameExpr().getNameAsString());
                        if (field != null) {
                            validateReflectiveWrite(field, call.getArgument(0).isNullLiteralExpr(), qualified, call, errors);
                        }
                    }
                }
            }
        }
    }

    private void bindReflectiveField(CompilationUnit unit, TypeDeclaration<?> owner, String qualified,
                                     String name, Expression value, Set<String> classAliases,
                                     Map<String, FieldDeclaration> fields) {
        boolean businessClass = isBusinessClass(unit, value, qualified, classAliases);
        FieldDeclaration field = null;
        if (value != null && value.isMethodCallExpr()) {
            MethodCallExpr lookup = value.asMethodCallExpr();
            if (lookup.getNameAsString().equals("getDeclaredField") && lookup.getArguments().size() == 1
                    && lookup.getArgument(0).isStringLiteralExpr()
                    && lookup.getScope().map(scope -> isBusinessClass(unit, scope, qualified, classAliases)).orElse(false)) {
                field = owner.getFieldByName(lookup.getArgument(0).asStringLiteralExpr().asString()).orElse(null);
            }
        }
        classAliases.remove(name);
        fields.remove(name);
        if (businessClass) {
            classAliases.add(name);
        }
        if (field != null) {
            fields.put(name, field);
        }
    }

    private boolean isBusinessClass(CompilationUnit unit, Expression expression, String qualified, Set<String> aliases) {
        return expression != null && (expression.isClassExpr()
                && matchesType(unit, expression.asClassExpr().getType().asString(), qualified)
                || expression.isNameExpr() && aliases.contains(expression.asNameExpr().getNameAsString()));
    }

    private void validateReflectiveWrite(FieldDeclaration field, boolean classTarget, String owner,
                                         MethodCallExpr call, List<String> errors) {
        if (field.isFinal()) {
            errors.add("禁止反射写真实 final 字段 " + owner + "." + field.getVariable(0).getNameAsString()
                    + ": " + call + "；setAccessible(true) 不能使 static final 可写。保留生产 final，"
                    + "只读获取缓存对象，按真实创建链隔离依赖，保存/替换/恢复对象内部非 final 字段；保留全部有效断言和验证");
        } else if (classTarget && !field.isStatic()) {
            errors.add("不能把真实实例字段当静态字段写入 " + owner + "." + field.getVariable(0).getNameAsString()
                    + ": " + call + "；Class/null 目标只适用于 static 字段。使用被测调用实际使用的实例，"
                    + "不能另造无关 Mock；若构造器已通过公开工厂注入依赖，删除多余的静态重置，保留断言");
        }
    }

    /** 针对已确认的 HttpClient 隔离错误预检，不将 AST 语法通过当作类型可访问性验证。 */
    private void validateHttpClientMocks(CompilationUnit unit, List<String> errors) {
        String internalClient = "org.apache.http.impl.client.InternalHttpClient";
        boolean internalImport = unit.getImports().stream()
                .anyMatch(declaration -> internalClient.equals(declaration.getNameAsString())
                        || declaration.getNameAsString().startsWith(internalClient + "."));
        if (internalImport || unit.findAll(ClassOrInterfaceType.class).stream()
                .anyMatch(type -> matchesType(unit, type, internalClient))) {
            errors.add("InternalHttpClient 是 Apache HttpClient 的包私有实现，测试不能 import、"
                    + "声明该类型或使用它的 class 字面量；全限定名不能绕过 Java 访问控制。"
                    + "不要改用 mockConstruction(CloseableHttpClient.class)，它是抽象类。"
                    + "按真实源码 Mock 公开的 HttpClients.custom()/HttpClientBuilder，"
                    + "让 builder.build() 返回普通 mock(CloseableHttpClient.class)，保留业务断言。");
        }
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!("mockConstruction".equals(call.getNameAsString())
                    || "mockConstructionWithAnswer".equals(call.getNameAsString()))
                    || call.getArguments().isEmpty() || !call.getArgument(0).isClassExpr()
                    || !call.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()) {
                continue;
            }
            ClassOrInterfaceType type = call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType();
            if (matchesType(unit, type, "org.apache.http.impl.client.CloseableHttpClient")) {
                errors.add("CloseableHttpClient 是抽象类，Mockito.mockConstruction 不能拦截其构造；"
                        + "普通 mock(CloseableHttpClient.class) 可用。必须按业务代码实际调用的公开工厂/"
                        + "Builder 注入 Mock，禁止替换成不可访问的 InternalHttpClient。");
            }
        }
    }

    private boolean matchesType(CompilationUnit unit, ClassOrInterfaceType type, String qualifiedName) {
        String name = type.getNameWithScope();
        if (qualifiedName.equals(name)) {
            return true;
        }
        int separator = qualifiedName.lastIndexOf('.');
        return qualifiedName.substring(separator + 1).equals(name) && unit.getImports().stream()
                .anyMatch(declaration -> !declaration.isStatic()
                        && (qualifiedName.equals(declaration.getNameAsString())
                        || declaration.isAsterisk() && qualifiedName.substring(0, separator)
                        .equals(declaration.getNameAsString())));
    }

    /** 仅检查已知公共 HTTP 类型，保留同名本地类型、显式导入和通配导入的解析空间。 */
    private void validateHttpClientReferences(CompilationUnit unit, List<String> errors) {
        String[] types = {"org.apache.http.impl.client.HttpClients", "org.apache.http.impl.client.HttpClientBuilder",
                "org.apache.http.impl.client.CloseableHttpClient", "org.apache.http.ProtocolVersion",
                "org.apache.http.HttpVersion", "org.apache.http.impl.conn.PoolingHttpClientConnectionManager",
                "org.apache.http.client.methods.HttpUriRequest", "org.apache.http.HttpEntityEnclosingRequest",
                "org.apache.http.entity.StringEntity", "org.apache.http.util.EntityUtils", "org.apache.http.Consts"};
        for (String qualified : types) {
            String name = qualified.substring(qualified.lastIndexOf('.') + 1);
            boolean used = unit.findAll(ClassOrInterfaceType.class).stream()
                    .anyMatch(type -> type.getNameWithScope().equals(name))
                    || unit.findAll(MethodCallExpr.class).stream()
                    .anyMatch(call -> call.getScope().map(scope -> scope.toString().equals(name)).orElse(false))
                    || unit.findAll(com.github.javaparser.ast.expr.MethodReferenceExpr.class).stream()
                    .anyMatch(reference -> reference.getScope().toString().equals(name))
                    || unit.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                    .anyMatch(field -> field.getScope().toString().equals(name));
            boolean bound = unit.getImports().stream().anyMatch(declaration -> !declaration.isStatic()
                    && (declaration.isAsterisk()
                    && declaration.getNameAsString().equals(qualified.substring(0, qualified.lastIndexOf('.')))
                    || !declaration.isAsterisk() && declaration.getNameAsString().endsWith("." + name)))
                    || unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
                    .anyMatch(type -> type.getNameAsString().equals(name))
                    || unit.findAll(com.github.javaparser.ast.type.TypeParameter.class).stream()
                    .anyMatch(type -> type.getNameAsString().equals(name))
                    || unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .anyMatch(variable -> variable.getNameAsString().equals(name))
                    || unit.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                    .anyMatch(parameter -> parameter.getNameAsString().equals(name))
                    || unit.getPackageDeclaration().map(pkg -> qualified.substring(0, qualified.lastIndexOf('.'))
                    .equals(pkg.getNameAsString())).orElse(false);
            if (used && !bound) {
                errors.add("HTTP 类型缺少导入: " + name + "；在同一计划局部补 import " + qualified
                        + "; 或使用真实全限定名，不能只新增调用代码。此检查不替代编译。");
            }
        }
        boolean invalidConstant = unit.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                .anyMatch(field -> ("HTTP_1_1".equals(field.getNameAsString())
                        || "HTTP_1_0".equals(field.getNameAsString()))
                        && matchesType(unit, field.getScope().toString(), "org.apache.http.ProtocolVersion")
                        && unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                        .noneMatch(variable -> variable.getNameAsString().equals(field.getScope().toString()))
                        && unit.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                        .noneMatch(parameter -> parameter.getNameAsString().equals(field.getScope().toString())))
                || unit.getImports().stream().anyMatch(declaration -> declaration.isStatic()
                && ("org.apache.http.ProtocolVersion.HTTP_1_1".equals(declaration.getNameAsString())
                || "org.apache.http.ProtocolVersion.HTTP_1_0".equals(declaration.getNameAsString())));
        if (invalidConstant) {
            errors.add("Apache HttpClient4 的 ProtocolVersion 没有 HTTP_1_1/HTTP_1_0 常量；"
                    + "使用 org.apache.http.HttpVersion.HTTP_1_1 或 new org.apache.http.ProtocolVersion(\"HTTP\", 1, 1)，"
                    + "不要在两个不存在的常量间切换。HttpClient4 与 5 的类型不能混用。");
        }
    }

    /** 只识别作用域明确的局部请求变量，不把普通响应的 getEntity 误当请求 API 错误。 */
    private void validateHttpRequestEntityAccess(CompilationUnit unit, List<String> errors) {
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("getEntity") && call.getArguments().isEmpty()
                        && call.getScope().filter(Expression::isNameExpr).isPresent()
                        && localHttpUriRequest(unit, method,
                        call.getScope().get().asNameExpr().getNameAsString()) != null) {
                    errors.add("HttpClient4 的 HttpUriRequest 未声明 getEntity(): " + call
                            + "；保留原请求体断言及预期，对同一捕获对象使用 ((HttpEntityEnclosingRequest) 对象).getEntity()，"
                            + "补 import org.apache.http.HttpEntityEnclosingRequest；无请求体时 build 可能返回不支持实体的请求，"
                            + "使用 对象 instanceof HttpEntityEnclosingRequest ? ((HttpEntityEnclosingRequest) 对象).getEntity() : null，"
                            + "不能换对象、盲目强转或删断言。");
                }
            }
        }
    }

    /** 在落盘和 Maven 前拦截类型已明确的不存在 API，普通 Header.getValue 不受影响。 */
    private void validateStringEntityBodyAccess(CompilationUnit unit, List<String> errors) {
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (stringEntityBodyReceiver(unit, method, call) != null) {
                    errors.add("Apache StringEntity 没有 getValue(): " + call
                            + "；读取同一请求体使用 org.apache.http.util.EntityUtils.toString(原实体, Consts.UTF_8)，"
                            + "补真实 import 和必要的 throws Exception。不得用实体自身的 toString()，"
                            + "保留原 assertEquals 预期、消息及全部验证，并修正本文件全部同类调用。");
                }
            }
        }
    }

    /** 只识别真实 StringEntity 转换或类型明确的唯一局部变量。 */
    private Expression stringEntityBodyReceiver(CompilationUnit unit, MethodDeclaration method, MethodCallExpr call) {
        if (!call.getNameAsString().equals("getValue") || !call.getArguments().isEmpty()
                || !call.getScope().isPresent() || unit.findAll(TypeDeclaration.class).stream()
                .anyMatch(type -> type.getNameAsString().equals("StringEntity"))) {
            return null;
        }
        Expression scope = call.getScope().get();
        while (scope.isEnclosedExpr()) {
            scope = scope.asEnclosedExpr().getInner();
        }
        if (scope.isCastExpr() && matchesType(unit, scope.asCastExpr().getType().asString(),
                "org.apache.http.entity.StringEntity")) {
            return httpEntityBodyExpression(unit, scope);
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            List<com.github.javaparser.ast.body.VariableDeclarator> variables = method
                    .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name))
                    .collect(java.util.stream.Collectors.toList());
            if (variables.size() == 1 && matchesType(unit, variables.get(0).getType().asString(),
                    "org.apache.http.entity.StringEntity")) {
                return scope;
            }
        }
        return null;
    }

    /** 比较实体本身，允许去掉读取正文时不必要的 StringEntity 强制转换。 */
    private Expression httpEntityBodyExpression(CompilationUnit unit, Expression expression) {
        while (true) {
            if (expression.isEnclosedExpr()) {
                expression = expression.asEnclosedExpr().getInner();
            } else if (expression.isCastExpr() && matchesType(unit, expression.asCastExpr().getType().asString(),
                    "org.apache.http.entity.StringEntity") && unit.findAll(TypeDeclaration.class).stream()
                    .noneMatch(type -> type.getNameAsString().equals("StringEntity"))) {
                expression = expression.asCastExpr().getExpression();
            } else {
                return expression;
            }
        }
    }

    private com.github.javaparser.ast.body.VariableDeclarator localHttpUriRequest(
            CompilationUnit unit, MethodDeclaration method, String name) {
        List<com.github.javaparser.ast.body.VariableDeclarator> locals = method.findAll(
                com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(name))
                .collect(java.util.stream.Collectors.toList());
        if (locals.size() != 1 || !locals.get(0).findAncestor(MethodDeclaration.class)
                .map(owner -> owner == method).orElse(false)
                || !matchesType(unit, locals.get(0).getType().asString(), "org.apache.http.client.methods.HttpUriRequest")
                || unit.findAll(TypeDeclaration.class).stream().anyMatch(type -> type.getNameAsString().equals("HttpUriRequest"))
                || method.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                .anyMatch(parameter -> parameter.getNameAsString().equals(name))
                || method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().toString().equals(name))) {
            return null;
        }
        return locals.get(0);
    }

    /** HttpClient4 的 execute 接收 build 后的请求，不能把构建器当成请求传入。 */
    private void validateHttpExecuteMatchers(CompilationUnit unit, List<String> errors) {
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("execute") && call.getArguments().size() == 1
                        && isHttpClientReceiver(unit, method, call)
                        && isTypedAny(unit, call.getArgument(0), "org.apache.http.client.methods.RequestBuilder")) {
                    errors.add("HttpClient4 execute 的单参数类型是 HttpUriRequest，不是 RequestBuilder: " + call
                            + "；stub 与 verify 都使用 any(HttpUriRequest.class)，补 import org.apache.http.client.methods.HttpUriRequest。"
                            + "保留接收者、次数及其它验证；不要改成无类型 any() 或强转来绕过匹配约束");
                }
            }
        }
    }

    private boolean isTypedAny(CompilationUnit unit, com.github.javaparser.ast.expr.Expression expression, String type) {
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr matcher = expression.asMethodCallExpr();
        return matcher.getNameAsString().equals("any") && matcher.getArguments().size() == 1
                && matcher.getArgument(0).isClassExpr()
                && matchesType(unit, matcher.getArgument(0).asClassExpr().getType().asString(), type)
                && unit.findAll(MethodDeclaration.class).stream().noneMatch(method -> method.getNameAsString().equals("any"))
                && (isMockitoCall(unit, matcher, "any") || matcher.getScope().map(scope ->
                matchesType(unit, scope.toString(), "org.mockito.ArgumentMatchers")).orElse(false)
                || !matcher.getScope().isPresent() && unit.getImports().stream().anyMatch(declaration ->
                declaration.isStatic() && (declaration.getNameAsString().equals("org.mockito.ArgumentMatchers.any")
                        || declaration.isAsterisk() && declaration.getNameAsString().equals("org.mockito.ArgumentMatchers"))));
    }

    private boolean isHttpClientReceiver(CompilationUnit unit, MethodDeclaration method, MethodCallExpr call) {
        com.github.javaparser.ast.expr.Expression receiver = call.getScope().orElse(null);
        if (receiver != null && receiver.isMethodCallExpr()) {
            MethodCallExpr verify = receiver.asMethodCallExpr();
            if (!isMockitoCall(unit, verify, "verify") || verify.getArguments().isEmpty()) {
                return false;
            }
            receiver = verify.getArgument(0);
        }
        if (receiver == null || !receiver.isNameExpr()) {
            return false;
        }
        String name = receiver.asNameExpr().getNameAsString();
        List<com.github.javaparser.ast.body.VariableDeclarator> locals = method.findAll(
                com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(name))
                .collect(java.util.stream.Collectors.toList());
        if (!locals.isEmpty()) {
            return locals.size() == 1 && matchesType(unit, locals.get(0).getType().asString(),
                    "org.apache.http.impl.client.CloseableHttpClient");
        }
        if (method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
            return method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(name)
                    && matchesType(unit, parameter.getType().asString(), "org.apache.http.impl.client.CloseableHttpClient"));
        }
        return method.findAncestor(ClassOrInterfaceDeclaration.class).map(owner -> owner.getFields().stream()
                .flatMap(field -> field.getVariables().stream()).anyMatch(variable -> variable.getNameAsString().equals(name)
                        && matchesType(unit, variable.getType().asString(), "org.apache.http.impl.client.CloseableHttpClient")))
                .orElse(false);
    }

    /** 只拦截 setup 内的 HTTP 工厂 Mock 在后续测试显式触发构造前已被关闭的已知模式。 */
    private void validateHttpMockLifetime(CompilationUnit unit, List<String> errors) {
        boolean laterClientCreation = unit.findAll(MethodDeclaration.class).stream().filter(method -> isTest(unit, method))
                .anyMatch(method -> method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                        .anyMatch(creation -> "HttpClientUtil".equals(creation.getType().getNameAsString())));
        boolean laterInitialization = unit.findAll(MethodDeclaration.class).stream().filter(method -> isTest(unit, method))
                .anyMatch(method -> method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                        .anyMatch(creation -> "HttpClientUtil".equals(creation.getType().getNameAsString()))
                        || method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        ("mockConstruction".equals(call.getNameAsString())
                                || "mockConstructionWithAnswer".equals(call.getNameAsString()))
                                && !call.getArguments().isEmpty() && call.getArgument(0).isClassExpr()
                                && call.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                                && (call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType()
                                .getNameAsString().equals("AhiHttpInvokerHandler")
                                || call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType()
                                .getNameAsString().equals("HttpClientUtil"))));
        if (!laterInitialization) {
            return;
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (method.getAnnotations().stream().noneMatch(annotation ->
                    annotation.getName().getIdentifier().equals("BeforeEach")
                            || annotation.getName().getIdentifier().equals("Before"))) {
                continue;
            }
            // 若 setup 已显式完成 Handler 初始化，后续仅 Mock 该 Handler 构造不再需要 HTTP 工厂。
            boolean initializedHandler = method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                    .anyMatch(creation -> creation.getType().getNameAsString().equals("AhiHttpInvokerHandler"))
                    || method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    call.getNameAsString().equals("forName") && call.getScope()
                            .map(scope -> scope.toString().equals("Class") || scope.toString().equals("java.lang.Class"))
                            .orElse(false) && call.getArguments().size() == 1 && call.getArgument(0).isStringLiteralExpr()
                            && call.getArgument(0).asStringLiteralExpr().asString().endsWith(".AhiHttpInvokerHandler"));
            if (initializedHandler && !laterClientCreation) {
                continue;
            }
            for (com.github.javaparser.ast.body.VariableDeclarator variable :
                    method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
                boolean httpFactory = variable.getInitializer().map(initializer -> initializer.isMethodCallExpr()
                        && initializer.asMethodCallExpr().getNameAsString().equals("mockStatic")
                        && initializer.asMethodCallExpr().getArguments().size() >= 1
                        && initializer.asMethodCallExpr().getArgument(0).isClassExpr()
                        && initializer.asMethodCallExpr().getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                        && matchesType(unit, initializer.asMethodCallExpr().getArgument(0).asClassExpr()
                        .getType().asClassOrInterfaceType(), "org.apache.http.impl.client.HttpClients")).orElse(false);
                if (!httpFactory) {
                    continue;
                }
                boolean closed = method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> call.getNameAsString().equals("close") && call.getScope()
                                .map(scope -> scope.toString().equals(variable.getNameAsString())).orElse(false))
                        || method.findAll(com.github.javaparser.ast.stmt.TryStmt.class).stream()
                        .anyMatch(statement -> statement.getResources().stream().anyMatch(resource ->
                                resource.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                                        .contains(variable)));
                if (closed) {
                    errors.add("HttpClients 的局部 Mock 在 " + method.getNameAsString()
                            + " 结束前已关闭，但测试方法仍会触发 HttpClientUtil/AhiHttpInvokerHandler 构造。"
                            + "将 Mock 句柄保存到字段并在 AfterEach/After 关闭，或在同一测试的 try-with-resources "
                            + "内覆盖初始化、业务调用与验证；不能在 setup 的 finally 提前关闭。");
                }
            }
        }
    }

    /** 只收集真实源码中直接由 ReadConfig getter 初始化的已知 final 请求地址。 */
    private void collectCachedConfigurationGetters(CompilationUnit business, Set<String> getters) {
        Set<String> configurations = business.findAll(FieldDeclaration.class).stream()
                .flatMap(field -> field.getVariables().stream())
                .filter(variable -> variable.getType().isClassOrInterfaceType()
                        && variable.getType().asClassOrInterfaceType().getNameAsString().equals("ReadConfig"))
                .map(variable -> variable.getNameAsString()).collect(java.util.stream.Collectors.toSet());
        business.findAll(FieldDeclaration.class).stream().filter(field -> field.isStatic() && field.isFinal())
                .flatMap(field -> field.getVariables().stream()).forEach(variable -> variable.getInitializer()
                .filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr)
                .filter(call -> call.getNameAsString().equals("getAhiHttpBusinessUrl") && call.getArguments().isEmpty()
                        && call.getScope().map(scope -> configurations.contains(scope.toString())).orElse(false))
                .ifPresent(call -> getters.add(call.getNameAsString())));
    }

    /** 检查共享 setup 的连接池及真实静态地址夹具，方法内的非法配置用例不在此处拦截。 */
    private void validateHttpPoolConfiguration(CompilationUnit unit, Set<String> cachedConfigurationGetters,
                                                List<String> errors) {
        java.util.Set<String> configurations = new java.util.HashSet<>();
        for (com.github.javaparser.ast.body.VariableDeclarator variable :
                unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (variable.getType().isClassOrInterfaceType()
                    && variable.getType().asClassOrInterfaceType().getNameAsString().equals("ReadConfig")) {
                configurations.add(variable.getNameAsString());
            }
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (method.getAnnotations().stream().noneMatch(annotation ->
                    annotation.getName().getIdentifier().equals("BeforeEach")
                            || annotation.getName().getIdentifier().equals("Before"))) {
                continue;
            }
            validateUnconfiguredPoolMock(unit, method, configurations, cachedConfigurationGetters, errors);
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("thenReturn") || call.getArguments().isEmpty()
                        || !call.getScope().isPresent() || !call.getScope().get().isMethodCallExpr()) {
                    continue;
                }
                MethodCallExpr stub = call.getScope().get().asMethodCallExpr();
                if (!stub.getNameAsString().equals("when") || stub.getArguments().size() != 1
                        || !stub.getArgument(0).isMethodCallExpr()) {
                    continue;
                }
                MethodCallExpr getter = stub.getArgument(0).asMethodCallExpr();
                if (!getter.getScope().isPresent()
                        || !configurations.contains(getter.getScope().get().toString())) {
                    continue;
                }
                com.github.javaparser.ast.expr.Expression value = call.getArgument(0);
                if (cachedConfigurationGetters.contains(getter.getNameAsString())) {
                    if (value.isNullLiteralExpr() || value.isStringLiteralExpr()
                            && value.asStringLiteralExpr().asString().trim().isEmpty()) {
                        errors.add("共享初始化中的 " + getter.getNameAsString()
                                + " 明确返回空地址，真实 Handler 的 static final REQUEST_URL 会永久缓存该值。"
                                + "首次构造器 Mock 前配置有效测试地址，复用已有 Handler 测试的 URL，不反射写 final。");
                    }
                    continue;
                }
                if (!(getter.getNameAsString().equals("getHttpMaxtotalConnections")
                        || getter.getNameAsString().equals("getHttpMaxperrouteConnections"))) {
                    continue;
                }
                boolean negative = value.isUnaryExpr() && value.asUnaryExpr().getOperator()
                        == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS
                        && value.asUnaryExpr().getExpression().isIntegerLiteralExpr();
                boolean zero = value.isIntegerLiteralExpr() && value.asIntegerLiteralExpr().asInt() == 0;
                if (negative || zero) {
                    errors.add("共享初始化中的 " + getter.getNameAsString()
                            + " 明确返回非正数，真实连接池会报 Max value may not be negative or zero。"
                            + "正常夹具应使用正数；刻意验证非法配置的 stub 应放在对应测试方法中，不能污染全部用例。");
                }
            }
        }
    }

    /** 对可直接证明的普通 Mock 默认0给出前置反馈；自定义 Answer、辅助夹具或连接池隔离留给运行验证。 */
    private void validateUnconfiguredPoolMock(CompilationUnit unit, MethodDeclaration setup,
                                              java.util.Set<String> configurations, Set<String> cachedConfigurationGetters,
                                              List<String> errors) {
        if (unit.findAll(ClassOrInterfaceDeclaration.class).stream().anyMatch(type -> !type.getExtendedTypes().isEmpty())
                || unit.findAll(AnnotationExpr.class).stream().anyMatch(annotation -> java.util.Arrays.asList(
                "BeforeAll", "BeforeClass", "SpringBootTest", "ContextConfiguration")
                .contains(annotation.getName().getIdentifier()))) {
            return;
        }
        boolean http = unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                call.getNameAsString().equals("mockConstruction") && !call.getArguments().isEmpty()
                        && call.getArgument(0).isClassExpr()
                        && call.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                        && call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType()
                        .getNameAsString().equals("AhiHttpInvokerHandler"));
        boolean poolIsolated = unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                call.getNameAsString().startsWith("mockConstruction") && !call.getArguments().isEmpty()
                        && call.getArgument(0).isClassExpr()
                        && call.getArgument(0).asClassExpr().getType().asString().endsWith("PoolingHttpClientConnectionManager"));
        // catch 中的 tearDown 只在失败路径清理资源，不代表正常路径已通过辅助方法准备容量。
        boolean helper = setup.findAll(MethodCallExpr.class).stream()
                .filter(call -> !call.findAncestor(com.github.javaparser.ast.stmt.CatchClause.class).isPresent())
                .anyMatch(call ->
                unit.getTypes().stream().flatMap(type -> type.getMethods().stream())
                        .anyMatch(method -> !method.equals(setup) && method.getNameAsString().equals(call.getNameAsString()))
                        || call.getScope().map(scope -> scope.isSuperExpr()).orElse(false)
                        || !call.getScope().isPresent() && !java.util.Arrays.asList("mock", "mockStatic", "when", "thenReturn")
                        .contains(call.getNameAsString()));
        if (!http || poolIsolated || helper) {
            return;
        }
        for (String configuration : configurations) {
            boolean plainMock = setup.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                    .anyMatch(assignment -> assignment.getTarget().toString().equals(configuration)
                            && isPlainConfigMock(assignment.getValue()))
                    || setup.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .anyMatch(variable -> variable.getNameAsString().equals(configuration)
                            && variable.getInitializer().map(this::isPlainConfigMock).orElse(false));
            boolean bean = setup.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    call.getNameAsString().equals("thenReturn") && call.getArguments().size() == 1
                            && call.getArgument(0).toString().equals(configuration)
                            && call.getScope().map(scope -> scope.findAll(MethodCallExpr.class).stream().anyMatch(getter ->
                            getter.getNameAsString().equals("getBean") && getter.getArguments().size() == 1
                                    && getter.getArgument(0).isClassExpr()
                                    && getter.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                                    && getter.getArgument(0).asClassExpr().getType().asClassOrInterfaceType()
                                    .getNameAsString().equals("ReadConfig"))).orElse(false));
            boolean indirectSetup = setup.findAll(MethodCallExpr.class).stream()
                    .filter(call -> !call.findAncestor(com.github.javaparser.ast.stmt.CatchClause.class).isPresent())
                    .anyMatch(call ->
                    !call.getNameAsString().equals("thenReturn")
                            && call.getArguments().stream().anyMatch(argument -> argument.toString().equals(configuration)));
            if (!plainMock || !bean || indirectSetup) {
                continue;
            }
            List<String> requiredGetters = new ArrayList<>(java.util.Arrays.asList(
                    "getHttpMaxtotalConnections", "getHttpMaxperrouteConnections"));
            requiredGetters.addAll(cachedConfigurationGetters);
            for (String getter : requiredGetters) {
                boolean configured = setup.findAll(MethodCallExpr.class).stream()
                        .filter(call -> !call.findAncestor(com.github.javaparser.ast.stmt.CatchClause.class).isPresent())
                        .anyMatch(call -> isPoolConfigurationStub(unit, call, configuration, getter));
                if (!configured) {
                    if (cachedConfigurationGetters.contains(getter)) {
                        errors.add("普通 ReadConfig Mock " + configuration + " 未配置 " + getter
                                + "，默认 null 会在首次 mockConstruction(AhiHttpInvokerHandler.class) 时固化到"
                                + "真实源码的 static final REQUEST_URL。正常 setup 中先配置有效地址，"
                                + "复用本任务已有 Handler 测试 URL 或完整配置夹具，不修改生产 final。");
                        continue;
                    }
                    errors.add("普通 ReadConfig Mock " + configuration + " 已作为 Spring Bean 返回，但未配置 "
                            + getter + "，其默认返回0；mockConstruction(AhiHttpInvokerHandler.class) 仍会触发"
                            + "静态 HttpClientUtil 的真实连接池初始化。首次使用前为两个连接池容量设置正数，"
                            + "或改用完整真实配置夹具，不能删除业务静态初始化。");
                }
            }
        }
    }

    private boolean isPoolConfigurationStub(CompilationUnit unit, MethodCallExpr call,
                                              String configuration, String getter) {
        if (!call.getNameAsString().equals("thenReturn") || call.getArguments().isEmpty()
                || !call.getScope().filter(Expression::isMethodCallExpr).isPresent()) {
            return false;
        }
        MethodCallExpr stub = call.getScope().get().asMethodCallExpr();
        if (!isMockitoCall(unit, stub, "when") || stub.getArguments().size() != 1
                || !stub.getArgument(0).isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr read = stub.getArgument(0).asMethodCallExpr();
        return read.getNameAsString().equals(getter) && read.getArguments().isEmpty()
                && read.getScope().map(scope -> scope.toString().equals(configuration)).orElse(false);
    }

    private boolean isPlainConfigMock(com.github.javaparser.ast.expr.Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        return call.getNameAsString().equals("mock") && call.getArguments().size() == 1
                && call.getArgument(0).isClassExpr() && call.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                && call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType().getNameAsString().equals("ReadConfig");
    }

    /** 反射写静态字段也会触发原静态初始化，不能用它绕过首次 Spring Bean 准备。 */
    private void validateReflectiveHttpInitialization(CompilationUnit unit, List<String> errors) {
        boolean customInitialization = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(type -> !type.getExtendedTypes().isEmpty())
                || unit.findAll(AnnotationExpr.class).stream().anyMatch(annotation ->
                java.util.Arrays.asList("BeforeAll", "BeforeClass").contains(annotation.getName().getIdentifier()));
        if (customInitialization) {
            return;
        }
        for (MethodDeclaration setup : unit.findAll(MethodDeclaration.class)) {
            if (setup.getAnnotations().stream().noneMatch(annotation -> java.util.Arrays.asList("BeforeEach", "Before")
                    .contains(annotation.getName().getIdentifier()))) {
                continue;
            }
            boolean helper = setup.findAll(MethodCallExpr.class).stream().anyMatch(call -> !call.getScope().isPresent()
                    && unit.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                    method != setup && method.getNameAsString().equals(call.getNameAsString())));
            boolean reflectiveWrite = setup.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    call.getNameAsString().equals("setField") && call.getArguments().size() == 3
                            && call.getScope().map(scope -> matchesType(unit, scope.toString(),
                            "org.springframework.test.util.ReflectionTestUtils")).orElse(false)
                            && call.getArgument(0).isClassExpr()
                            && call.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                            && call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType().getNameAsString().equals("HttpClientUtil"));
            boolean contextMock = setup.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    call.getNameAsString().equals("mock") && call.getArguments().size() == 1
                            && call.getArgument(0).isClassExpr() && matchesType(unit,
                            call.getArgument(0).asClassExpr().getType().asString(), "org.springframework.context.ApplicationContext"));
            boolean springMock = setup.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    isMockitoCall(unit, call, "mockStatic") && call.getArguments().size() == 1
                            && call.getArgument(0).isClassExpr()
                            && call.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                            && call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType()
                            .getNameAsString().equals("SpringContextBeanUtil"));
            if (helper || !reflectiveWrite || !contextMock || !springMock) {
                continue;
            }
            for (String bean : java.util.Arrays.asList("ReadConfig", "BeeTraceClientHelper")) {
                boolean prepared = unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        call.getNameAsString().equals("getBean") && call.getArguments().size() == 1
                                && call.getArgument(0).isClassExpr()
                                && call.getArgument(0).asClassExpr().getType().isClassOrInterfaceType()
                                && call.getArgument(0).asClassExpr().getType().asClassOrInterfaceType().getNameAsString().equals(bean));
                if (!prepared) {
                    errors.add("HttpClientUtil 反射写静态字段之前仍会先执行原 static 初始化，但普通 ApplicationContext Mock 未准备 "
                            + bean + " Bean；先在首次 setField/主动使用前 stub getBean(" + bean
                            + ".class)，准备有效配置。类初始化后再保存、注入和恢复可变字段，不能靠反射赋值跳过 static 块");
                }
            }
        }
    }

    public Map<String, Integer> assertions(String source) {
        return assertionCounts(parse(source));
    }

    private Map<String, Integer> assertionCounts(CompilationUnit unit) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (isTest(unit, method)) {
                result.put(method.getSignature().asString(), assertionCount(unit, method));
            }
        }
        return result;
    }

    public void preserveAssertions(String before, String after, String businessSource,
                                   String testClass, List<String> verifyErrors) {
        preserveAssertions(before, after, businessSource, testClass, verifyErrors, java.util.Collections.emptyMap());
    }

    public void preserveAssertions(String before, String after, String businessSource,
                                   String testClass, List<String> verifyErrors, Map<String, String> jsonProperties) {
        preserveAssertions(before, after, businessSource, testClass, verifyErrors, jsonProperties, businessSource);
    }

    public void preserveAssertions(String before, String after, String businessSource,
                                   String testClass, List<String> verifyErrors, Map<String, String> jsonProperties,
                                   String requestHeaderSource) {
        CompilationUnit previous = parse(before);
        CompilationUnit next = parse(after);
        normalizePrivateFieldAssertions(previous, next, businessSource, testClass, verifyErrors);
        normalizeRequestJsonAssertions(previous, next, businessSource, jsonProperties);
        normalizeLombokToStringAssertions(previous, next, parse(businessSource));
        normalizeDisabledConstructionAssertions(previous, next, parse(businessSource));
        normalizeHttpExecuteMatchers(previous, next, parse(businessSource));
        normalizeHttpRequestEntityCasts(previous, next, verifyErrors);
        normalizeStringEntityBodyAssertions(previous, next);
        normalizeJsonContentTypeAssertions(previous, next, testClass, verifyErrors, requestHeaderSource);
        normalizeRequestDateAssertions(previous, next, businessSource);
        Map<String, Integer> previousCounts = assertionCounts(previous);
        Map<String, Integer> nextCounts = assertionCounts(next);
        for (Map.Entry<String, Integer> method : previousCounts.entrySet()) {
            if (!nextCounts.containsKey(method.getKey()) || nextCounts.get(method.getKey()) < method.getValue()) {
                throw new IllegalArgumentException("禁止删除既有测试方法或减少有效断言: " + testClass + " " + method.getKey());
            }
        }
        Map<String, Integer> calls = assertionCalls(next);
        Map<String, Integer> corrections = exceptionAssertionCorrections(
                previous, next, businessSource, testClass, verifyErrors);
        addImpossibleAssertionCorrections(previous, next, businessSource, corrections);
        for (Map.Entry<String, Integer> call : assertionCalls(previous).entrySet()) {
            if (calls.getOrDefault(call.getKey(), 0) + corrections.getOrDefault(call.getKey(), 0)
                    < call.getValue()) {
                String hint = call.getKey().contains(".execute(") && call.getKey().contains("RequestBuilder.class")
                        ? "；已确认的 HttpClient4 参数修正须使用 any(HttpUriRequest.class)，保留原 verify 接收者及次数。"
                        + "(HttpUriRequest) any() 是无类型匹配，不能用强转放宽该约束；stub 和 verify 一起修复并补真实 import。"
                        : call.getKey().contains("getFirstHeader(") && call.getKey().contains("application/json")
                        ? "；Content-Type 纠正须有本次用例失败与真实 Handler 设置依据，仅把裸 MIME 预期改为 "
                        + "ContentType.APPLICATION_JSON.toString()（补真实 import）或完整 application/json; charset=UTF-8；"
                        + "保留原 getFirstHeader(...).getValue()、捕获对象、断言和消息，不改 contains/startsWith 或截取 MIME。"
                        : call.getKey().contains("StringEntity") && call.getKey().contains("getValue()")
                        ? "；StringEntity 没有 getValue。保持原 assertEquals 的预期、消息及捕获来源，"
                        + "只将原实际值替换为 EntityUtils.toString(同一实体表达式, Consts.UTF_8)，补真实 import；"
                        + "不要新增正文别名/helper 改写断言，不要用普通 toString 或常量替代读取。" : "";
                throw new IllegalArgumentException("禁止删除或放宽已有断言/交互验证: " + call.getKey() + hint);
            }
        }
    }

    /** 静态证据只适用于本请求协议：经办日及请求时间均来自同一次上海时钟。 */
    private boolean hasRequestDateContract(String source) {
        if (source == null || source.trim().isEmpty()) {
            return false;
        }
        CompilationUnit business = parse(source);
        for (MethodDeclaration method : business.findAll(MethodDeclaration.class)) {
            if (!method.getNameAsString().equals("buildRequestBody")) {
                continue;
            }
            List<com.github.javaparser.ast.body.VariableDeclarator> dates = method
                    .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals("bsrDate") && variable.getInitializer().isPresent())
                    .collect(java.util.stream.Collectors.toList());
            List<com.github.javaparser.ast.body.VariableDeclarator> times = method
                    .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals("requestTime")
                            && variable.getInitializer().filter(value -> value.isMethodCallExpr()
                            && value.asMethodCallExpr().getNameAsString().equals("getDdApdatestr")
                            && value.asMethodCallExpr().getArguments().isEmpty()).isPresent())
                    .collect(java.util.stream.Collectors.toList());
            boolean clock = business.findAll(MethodDeclaration.class).stream().filter(value ->
                    value.getNameAsString().equals("getDdApdatestr")).flatMap(value ->
                    value.findAll(com.github.javaparser.ast.stmt.ReturnStmt.class).stream())
                    .anyMatch(value -> value.getExpression().map(expression ->
                            isShanghaiTimeFormat(business, expression, "yyyyMMddHHmmss")).orElse(false));
            if (dates.size() != 1 || times.size() != 1 || !clock) {
                continue;
            }
            Expression date = dates.get(0).getInitializer().get();
            boolean dateSource = date.toString().equals("requestTime.substring(0, 8)")
                    || isShanghaiTimeFormat(business, date, "yyyyMMdd");
            boolean writesDate = method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    call.getNameAsString().equals("setBsrOprDat") && call.getArguments().size() == 1
                            && call.getArgument(0).toString().equals("bsrDate"));
            boolean writesTime = method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    call.getNameAsString().equals("setRequestTime") && call.getArguments().size() == 1
                            && call.getArgument(0).toString().equals("requestTime"));
            boolean reassigned = method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                    .anyMatch(assignment -> java.util.Arrays.asList("bsrDate", "requestTime")
                            .contains(assignment.getTarget().toString()));
            if (dateSource && writesDate && writesTime && !reassigned) {
                return true;
            }
        }
        return false;
    }

    private boolean isShanghaiTimeFormat(CompilationUnit unit, Expression value, String pattern) {
        if (!value.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr format = value.asMethodCallExpr();
        if (!format.getNameAsString().equals("format") || format.getArguments().size() != 1
                || !format.getScope().filter(Expression::isMethodCallExpr).isPresent()) {
            return false;
        }
        MethodCallExpr now = format.getScope().get().asMethodCallExpr();
        if (!now.getNameAsString().equals("now") || now.getArguments().size() != 1
                || !now.getScope().map(scope -> isHttpClassReference(unit, scope, "java.time.LocalDateTime")).orElse(false)
                || !now.getArgument(0).isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr zone = now.getArgument(0).asMethodCallExpr();
        if (!zone.getNameAsString().equals("of") || zone.getArguments().size() != 1
                || !zone.getArgument(0).isStringLiteralExpr()
                || !zone.getArgument(0).asStringLiteralExpr().asString().equals("Asia/Shanghai")
                || !zone.getScope().map(scope -> isHttpClassReference(unit, scope, "java.time.ZoneId")).orElse(false)) {
            return false;
        }
        Expression formatter = format.getArgument(0);
        if (formatter.isNameExpr()) {
            List<FieldDeclaration> fields = unit.findAll(FieldDeclaration.class).stream().filter(field ->
                    field.isStatic() && field.isFinal() && field.getVariables().size() == 1
                            && field.getVariable(0).getNameAsString().equals(format.getArgument(0).toString()))
                    .collect(java.util.stream.Collectors.toList());
            if (fields.size() != 1 || !fields.get(0).getVariable(0).getInitializer().isPresent()) {
                return false;
            }
            formatter = fields.get(0).getVariable(0).getInitializer().get();
        }
        if (!formatter.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr factory = formatter.asMethodCallExpr();
        return factory.getNameAsString().equals("ofPattern") && factory.getArguments().size() == 1
                && factory.getArgument(0).isStringLiteralExpr()
                && factory.getArgument(0).asStringLiteralExpr().asString().equals(pattern)
                && factory.getScope().map(scope -> isHttpClassReference(unit, scope,
                "java.time.format.DateTimeFormatter")).orElse(false);
    }

    private boolean isRequestDateRead(Expression value) {
        return value.isMethodCallExpr() && value.asMethodCallExpr().getNameAsString().equals("asText")
                && value.asMethodCallExpr().getArguments().isEmpty()
                && value.asMethodCallExpr().getScope().filter(Expression::isMethodCallExpr).map(scope -> {
                    MethodCallExpr get = scope.asMethodCallExpr();
                    return get.getNameAsString().equals("get") && get.getArguments().size() == 1
                            && get.getArgument(0).isStringLiteralExpr()
                            && get.getArgument(0).asStringLiteralExpr().asString().equals("Bsr_Opr_Dat")
                            && get.getScope().filter(Expression::isNameExpr).isPresent();
                }).orElse(false);
    }

    private void validateRequestDateAssertions(CompilationUnit unit, CompilationUnit business, List<String> errors) {
        if (!hasRequestDateContract(business.toString())) {
            return;
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!isTest(unit, method)) {
                continue;
            }
            method.findAll(MethodCallExpr.class).stream().filter(call -> isJupiterAssertion(unit, call, "assertEquals")
                    && call.getArguments().size() >= 2 && call.getArgument(0).isStringLiteralExpr()
                    && call.getArgument(0).asStringLiteralExpr().asString().matches("[0-9]{8}")
                    && isRequestDateRead(call.getArgument(1))).forEach(call ->
                    errors.add("动态日期断言错误: " + method.getNameAsString()
                            + " 的 Bsr_Opr_Dat 来自上海时区当前请求时间，不能固定日期；"
                            + "保留原 assertEquals/实际值/消息，用同一 JSON 的 header.get(\"requestTime\").asText().substring(0, 8)"
                            + "作预期，header/data 必须来自同一 root 的 request_header/request_data，保留局部变量及初值。"
                            + "不要更改来自 expDat 输入的 Bsr_Ept_Dat，不可改业务迁就固定日期"));
        }
    }

    /** 预检已证明固定日期不成立，只纠正同一请求 JSON 中日期的来源关系。 */
    private void normalizeRequestDateAssertions(CompilationUnit previous, CompilationUnit next, String source) {
        if (!hasRequestDateContract(source)) {
            return;
        }
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr original : method.findAll(MethodCallExpr.class)) {
                if (!isJupiterAssertion(previous, original, "assertEquals") || original.getArguments().size() < 2
                        || original.getArguments().size() > 3 || !original.getArgument(0).isStringLiteralExpr()
                        || !original.getArgument(0).asStringLiteralExpr().asString().matches("[0-9]{8}")
                        || !isRequestDateRead(original.getArgument(1))) {
                    continue;
                }
                for (MethodDeclaration candidate : next.findAll(MethodDeclaration.class)) {
                    if (!candidate.getSignature().equals(method.getSignature()) || !isTest(next, candidate)) {
                        continue;
                    }
                    for (MethodCallExpr replacement : candidate.findAll(MethodCallExpr.class)) {
                        if (!isJupiterAssertion(next, replacement, "assertEquals")
                                || replacement.getArguments().size() != original.getArguments().size()) {
                            continue;
                        }
                        Expression expected = replacement.getArgument(0);
                        String data = original.getArgument(1).asMethodCallExpr().getScope().get()
                                .asMethodCallExpr().getScope().get().toString();
                        List<com.github.javaparser.ast.body.VariableDeclarator> headers = candidate
                                .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                                .filter(variable -> expected.toString().equals(variable.getNameAsString()
                                        + ".get(\"requestTime\").asText().substring(0, 8)"))
                                .collect(java.util.stream.Collectors.toList());
                        if (headers.size() != 1) {
                            continue;
                        }
                        String header = headers.get(0).getNameAsString();
                        String root = requestJsonRoot(next, candidate, data, "request_data");
                        if (root == null || !root.equals(requestJsonRoot(next, candidate, header, "request_header"))
                                || !sameEntityBodyInputs(method, candidate, new com.github.javaparser.ast.expr.NameExpr(data))
                                || !sameEntityBodyInputs(method, candidate, new com.github.javaparser.ast.expr.NameExpr(header))
                                || !sameEntityBodyInputs(method, candidate, new com.github.javaparser.ast.expr.NameExpr(root))) {
                            continue;
                        }
                        MethodCallExpr normalized = replacement.clone();
                        normalized.setArgument(0, original.getArgument(0).clone());
                        if (normalized.equals(original)) {
                            replacement.setArgument(0, original.getArgument(0).clone());
                        }
                    }
                }
            }
        }
    }

    private String requestJsonRoot(CompilationUnit unit, MethodDeclaration method, String variable, String key) {
        List<com.github.javaparser.ast.body.VariableDeclarator> locals = method
                .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .filter(value -> value.getNameAsString().equals(variable)
                        && matchesType(unit, value.getType().asString(), "com.fasterxml.jackson.databind.JsonNode")
                        && value.getInitializer().filter(Expression::isMethodCallExpr).isPresent())
                .collect(java.util.stream.Collectors.toList());
        if (locals.size() != 1) {
            return null;
        }
        MethodCallExpr get = locals.get(0).getInitializer().get().asMethodCallExpr();
        return get.getNameAsString().equals("get") && get.getArguments().size() == 1
                && get.getArgument(0).isStringLiteralExpr() && get.getArgument(0).asStringLiteralExpr().asString().equals(key)
                && get.getScope().filter(Expression::isNameExpr).isPresent()
                ? get.getScope().get().toString() : null;
    }

    /** 模板专项可见证据检查，不以测试方法名或数量冒充分支覆盖率。 */
    private void validatePredictResponseCoverage(CompilationUnit unit, CompilationUnit business, List<String> errors) {
        boolean entry = business.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                method.getNameAsString().equals("ahiInvoke") && method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> call.getNameAsString().equals("getAsBigDecimal")));
        boolean handler = business.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                method.getNameAsString().equals("doPost") && method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> call.getNameAsString().equals("parse"))
                        && method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        call.getNameAsString().equals("invoke")));
        if (!entry && !handler) {
            return;
        }
        Set<String> covered = new HashSet<>();
        List<String> evidence = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!isTest(unit, method)) {
                continue;
            }
            boolean invokes = method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    java.util.Arrays.asList(entry ? "handle" : "doPost", entry ? "ahiInvoke" : "doPost")
                            .contains(call.getNameAsString()));
            if (!invokes) {
                continue;
            }
            Set<com.github.javaparser.ast.expr.StringLiteralExpr> payloads = connectedResponsePayloads(unit, method);
            for (com.github.javaparser.ast.expr.StringLiteralExpr literal : payloads) {
                String payload = literal.asString();
                if (payload.length() > 4096) {
                    continue;
                }
                // 这里只处理已接入响应的字面量，测试名、消息和未使用常量不作为覆盖证据。
                com.fasterxml.jackson.databind.JsonNode json;
                try {
                    json = mapper.readTree(payload);
                } catch (java.io.IOException exception) {
                    if (handler && payload.equals("not-json") && hasNullResponseAssertion(unit, method)) {
                        covered.add("非法 JSON");
                    }
                    continue;
                }
                if (json == null || !json.isObject()) {
                    continue;
                }
                if (handler && !json.has("response_header") && hasNullResponseAssertion(unit, method)) {
                    covered.add("缺响应头");
                }
                if (!entry || !"0000".equals(json.path("response_header").path("rst_code").asText())) {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode data = json.get("response_data");
                if (hasFailureWrites(unit, method)) {
                    if (data == null || data.isNull()) {
                        covered.add("缺响应数据");
                    } else if (data.isObject()) {
                        boolean validScore = data.path("predict_rescale_score").isNumber();
                        boolean validTime = data.path("time").isIntegralNumber();
                        if (!data.has("predict_rescale_score") && validTime) {
                            covered.add("缺评分");
                        }
                        if (!data.has("time") && validScore) {
                            covered.add("缺耗时");
                        }
                        if (data.path("predict_rescale_score").asText().equals("bad-score") && validTime) {
                            covered.add("非法评分");
                        }
                        if (data.path("time").asText().equals("bad-time") && validScore) {
                            covered.add("非法耗时");
                        }
                    }
                }
                if (data != null && data.path("predict_rescale_score").isNumber() && data.path("time").isIntegralNumber()) {
                    String score = data.get("predict_rescale_score").asText();
                    if (java.util.Arrays.asList("0.1", "0.123456").contains(score)
                            && hasExactWrite(unit, method, "predict_rescale_score", "new BigDecimal(\"" + score + "\")")
                            && hasExactWrite(unit, method, "ahiResultAvailable", "\"1\"")) {
                        covered.add("精度 " + score);
                    }
                }
            }
            if (evidence.size() < 8) {
                evidence.add(method.getNameAsString() + "(响应已连接=" + !payloads.isEmpty()
                        + (handler ? ",null断言=" + hasNullResponseAssertion(unit, method) : "") + ")");
            }
        }
        List<String> required = entry ? java.util.Arrays.asList("精度 0.1", "精度 0.123456", "缺响应数据",
                "缺评分", "缺耗时", "非法评分", "非法耗时") : java.util.Arrays.asList("非法 JSON", "缺响应头");
        List<String> missing = required.stream().filter(item -> !covered.contains(item))
                .collect(java.util.stream.Collectors.toList());
        if (!missing.isEmpty()) {
            errors.add("模板响应测试缺少可见证据: " + missing
                    + "；已检查=" + evidence
                    + "；用完整 JSON 接入真实 HTTP execute 返回桩，允许无重赋值的局部变量、同类 setup 返回桩；"
                    + "响应夹具名不限，可同时接收状态码，但 StringEntity 必须接入该夹具实际返回的响应，不能仅声明不用。"
                    + (handler ? "Handler 用 not-json 和 {} 分别调用真实 doPost 并 assertNull 其返回值。" : "")
                    + (entry ? "入口异常使用 bad-score/bad-time，验证可用性0、评分BigDecimal.valueOf(2)、time=0、rst_code=0000，"
                    + "缺失/非法一个字段时另一个字段必须有效；精度验证可用性1及 new BigDecimal(\"0.1\")/new BigDecimal(\"0.123456\")。" : "")
                    + "保留原测试及全部有效断言");
        }
    }

    /** 沿局部初值追踪响应正文、实体和响应对象，未使用的 JSON 不能凑覆盖证据。 */
    private Set<com.github.javaparser.ast.expr.StringLiteralExpr> connectedResponsePayloads(
            CompilationUnit unit, MethodDeclaration method) {
        Set<com.github.javaparser.ast.expr.StringLiteralExpr> payloads = new java.util.LinkedHashSet<>();
        for (MethodCallExpr fixture : method.findAll(MethodCallExpr.class)) {
            if (fixture.getArguments().isEmpty() || fixture.getArguments().size() > 4
                    || fixture.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
                continue;
            }
            List<MethodDeclaration> helpers = unit.findAll(MethodDeclaration.class).stream()
                    .filter(helper -> helper.getNameAsString().equals(fixture.getNameAsString())
                            && helper.getParameters().size() == fixture.getArguments().size()
                            && helper.findAncestor(ClassOrInterfaceDeclaration.class)
                            .equals(method.findAncestor(ClassOrInterfaceDeclaration.class)))
                    .collect(java.util.stream.Collectors.toList());
            if (helpers.size() != 1 || !(matchesType(unit, helpers.get(0).getType().asString(),
                    "org.apache.http.client.methods.CloseableHttpResponse")
                    || matchesType(unit, helpers.get(0).getType().asString(), "org.apache.http.HttpResponse"))) {
                continue;
            }
            for (int index = 0; index < fixture.getArguments().size(); index++) {
                Expression body = responseValue(method, fixture.getArgument(index), 0);
                if (body != null && body.isStringLiteralExpr()
                        && responseFixtureUsesBody(unit, helpers.get(0), index)
                        && isReturnedByExecute(unit, method, fixture)) {
                    payloads.add(body.asStringLiteralExpr());
                }
            }
        }
        for (com.github.javaparser.ast.expr.ObjectCreationExpr entity : method.findAll(
                com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
            if (!matchesType(unit, entity.getType().asString(), "org.apache.http.entity.StringEntity")
                    || entity.getArguments().isEmpty()) {
                continue;
            }
            Expression body = responseValue(method, entity.getArgument(0), 0);
            if (body == null || !body.isStringLiteralExpr()) {
                continue;
            }
            for (MethodCallExpr stub : method.findAll(MethodCallExpr.class)) {
                Expression response = attachedEntityResponse(unit, method, stub, entity);
                if (response != null && isReturnedByExecute(unit, method, response)) {
                    payloads.add(body.asStringLiteralExpr());
                    break;
                }
            }
        }
        return payloads;
    }

    /** 按参数和返回对象证明夹具正文用途，不靠方法名或参数个数猜测响应。 */
    private boolean responseFixtureUsesBody(CompilationUnit unit, MethodDeclaration helper, int parameterIndex) {
        if (!java.util.Arrays.asList("String", "java.lang.String")
                .contains(helper.getParameter(parameterIndex).getType().asString())) {
            return false;
        }
        String parameter = helper.getParameter(parameterIndex).getNameAsString();
        List<com.github.javaparser.ast.stmt.ReturnStmt> returns = helper.findAll(
                com.github.javaparser.ast.stmt.ReturnStmt.class).stream()
                .filter(statement -> statement.findAncestor(MethodDeclaration.class).map(owner -> owner == helper).orElse(false))
                .collect(java.util.stream.Collectors.toList());
        if (returns.size() != 1 || !returns.get(0).getExpression().isPresent()) {
            return false;
        }
        Expression result = responseValue(helper, returns.get(0).getExpression().get(), 0);
        if (result == null) {
            return false;
        }
        for (com.github.javaparser.ast.expr.ObjectCreationExpr entity : helper.findAll(
                com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
            if (!matchesType(unit, entity.getType().asString(), "org.apache.http.entity.StringEntity")
                    || entity.getArguments().isEmpty()) {
                continue;
            }
            Expression body = responseValue(helper, entity.getArgument(0), 0);
            if (body == null || !body.isNameExpr() || !body.asNameExpr().getNameAsString().equals(parameter)) {
                continue;
            }
            if (helper.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    sameResponseValue(result, attachedEntityResponse(unit, helper, call, entity)))) {
                return true;
            }
        }
        return false;
    }

    private Expression attachedEntityResponse(CompilationUnit unit, MethodDeclaration method,
                                              MethodCallExpr call, Expression entity) {
        MethodCallExpr invocation = stubbedInvocation(unit, call);
        if (invocation != null && invocation.getNameAsString().equals("getEntity")
                && invocation.getArguments().isEmpty() && invocation.getScope().isPresent()
                && responseValue(method, call.getArgument(0), 0) == entity) {
            return responseValue(method, invocation.getScope().get(), 0);
        }
        if (call.getNameAsString().equals("setEntity") && call.getArguments().size() == 1
                && call.getScope().isPresent() && responseValue(method, call.getArgument(0), 0) == entity) {
            return responseValue(method, call.getScope().get(), 0);
        }
        return null;
    }

    /** setup 与用例只共享同类字段；不把另一个测试方法或同名局部对象的桩混进来。 */
    private boolean isReturnedByExecute(CompilationUnit unit, MethodDeclaration test, Expression response) {
        List<MethodDeclaration> scopes = new ArrayList<>();
        scopes.add(test);
        lifecycleMethods(unit, "BeforeEach", "Before").stream().filter(setup ->
                setup.findAncestor(ClassOrInterfaceDeclaration.class).equals(test.findAncestor(ClassOrInterfaceDeclaration.class)))
                .forEach(scopes::add);
        for (MethodDeclaration scope : scopes) {
            for (MethodCallExpr stub : scope.findAll(MethodCallExpr.class)) {
                if (!isExecuteReturn(unit, scope, stub)) {
                    continue;
                }
                Expression returned = responseValue(scope, stub.getArgument(0), 0);
                // setup 中的字段赋值是每例夹具初始化，不是测试内局部变量重赋值。
                if (returned == null && scope != test && isResponseFieldReference(scope, stub.getArgument(0))) {
                    returned = stub.getArgument(0);
                }
                if (sameResponseValue(response, returned)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isResponseFieldReference(MethodDeclaration method, Expression value) {
        if (!(value.isNameExpr() || value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr())) {
            return false;
        }
        String name = bindingName(value);
        return (value.isFieldAccessExpr() || method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .noneMatch(variable -> variable.getNameAsString().equals(name)))
                && method.findAncestor(ClassOrInterfaceDeclaration.class).map(owner -> owner.getFields().stream()
                .flatMap(field -> field.getVariables().stream()).anyMatch(variable -> variable.getNameAsString().equals(name)))
                .orElse(false);
    }

    /** 仅解析本方法唯一声明且未重赋值的局部变量，避免把变化后的响应误认成旧正文。 */
    private Expression responseValue(MethodDeclaration method, Expression value, int depth) {
        if (depth > 8) {
            return null;
        }
        if (value.isEnclosedExpr()) {
            return responseValue(method, value.asEnclosedExpr().getInner(), depth + 1);
        }
        if (!value.isNameExpr()) {
            return value;
        }
        String name = value.asNameExpr().getNameAsString();
        List<com.github.javaparser.ast.body.VariableDeclarator> variables = method.findAll(
                com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(name))
                .collect(java.util.stream.Collectors.toList());
        if (method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().toString().equals(name))) {
            return null;
        }
        if (variables.isEmpty()) {
            return value;
        }
        if (variables.size() != 1 || !variables.get(0).findAncestor(MethodDeclaration.class)
                .map(owner -> owner == method).orElse(false)
                || method.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                .anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
            return null;
        }
        return variables.get(0).getInitializer().map(initial -> responseValue(method, initial, depth + 1)).orElse(null);
    }

    private boolean sameResponseValue(Expression first, Expression second) {
        return first != null && second != null && (first == second
                || (first.isNameExpr() || first.isFieldAccessExpr() && first.asFieldAccessExpr().getScope().isThisExpr())
                && (second.isNameExpr() || second.isFieldAccessExpr() && second.asFieldAccessExpr().getScope().isThisExpr())
                && bindingName(first).equals(bindingName(second)));
    }

    private boolean isExecuteReturn(CompilationUnit unit, MethodDeclaration method, MethodCallExpr stub) {
        MethodCallExpr invocation = stubbedInvocation(unit, stub);
        return invocation != null && invocation.getNameAsString().equals("execute")
                && (isHttpClientReceiver(unit, method, invocation)
                || invocation.getScope().filter(scope -> scope.isFieldAccessExpr()
                && scope.asFieldAccessExpr().getScope().isThisExpr()).map(scope ->
                method.findAncestor(ClassOrInterfaceDeclaration.class).map(owner -> owner.getFields().stream()
                        .flatMap(field -> field.getVariables().stream()).anyMatch(variable ->
                        variable.getNameAsString().equals(bindingName(scope)) && matchesType(unit, variable.getType().asString(),
                                "org.apache.http.impl.client.CloseableHttpClient"))).orElse(false)).orElse(false));
    }

    private MethodCallExpr stubbedInvocation(CompilationUnit unit, MethodCallExpr call) {
        if (!call.getNameAsString().equals("thenReturn") || call.getArguments().size() != 1
                || !call.getScope().filter(Expression::isMethodCallExpr).isPresent()) {
            return null;
        }
        MethodCallExpr when = call.getScope().get().asMethodCallExpr();
        return isMockitoCall(unit, when, "when") && when.getArguments().size() == 1
                && when.getArgument(0).isMethodCallExpr() ? when.getArgument(0).asMethodCallExpr() : null;
    }

    private boolean hasNullResponseAssertion(CompilationUnit unit, MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream().anyMatch(call -> {
            boolean jupiter = isJupiterAssertion(unit, call, "assertNull");
            boolean junit4 = isJUnit4Assertion(unit, call, "assertNull");
            if ((!jupiter && !junit4) || call.getArguments().isEmpty() || call.getArguments().size() > 2) {
                return false;
            }
            int actualIndex = junit4 && call.getArguments().size() == 2 ? 1 : 0;
            Expression actual = responseValue(method, call.getArgument(actualIndex), 0);
            return actual != null && actual.isMethodCallExpr()
                    && actual.asMethodCallExpr().getNameAsString().equals("doPost");
        });
    }

    private boolean hasFailureWrites(CompilationUnit unit, MethodDeclaration method) {
        return hasExactWrite(unit, method, "ahiResultAvailable", "\"0\"")
                && hasExactWrite(unit, method, "predict_rescale_score", "BigDecimal.valueOf(2)")
                && hasExactWrite(unit, method, "time", "0")
                && hasExactWrite(unit, method, "rst_code", "\"0000\"");
    }

    private boolean hasExactWrite(CompilationUnit unit, MethodDeclaration method, String key, String expected) {
        return method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                call.getNameAsString().equals("putDataAndOriginData") && call.getArguments().size() == 2
                        && exactMatcherValue(unit, call.getArgument(0)).toString().equals("\"" + key + "\"")
                        && matchesExpectedWrite(unit, exactMatcherValue(unit, call.getArgument(1)), expected)
                        && call.getScope().filter(Expression::isMethodCallExpr).map(scope -> {
                            MethodCallExpr verify = scope.asMethodCallExpr();
                            return isMockitoCall(unit, verify, "verify") && (verify.getArguments().size() == 1
                                    || verify.getArguments().size() == 2 && isExactlyOnce(unit, verify.getArgument(1)));
                        }).orElse(false));
    }

    private boolean isExactlyOnce(CompilationUnit unit, Expression value) {
        return value.isMethodCallExpr() && isMockitoCall(unit, value.asMethodCallExpr(), "times")
                && value.asMethodCallExpr().getArguments().size() == 1
                && value.asMethodCallExpr().getArgument(0).toString().equals("1");
    }

    private boolean isJUnit4Assertion(CompilationUnit unit, MethodCallExpr call, String name) {
        return call.getNameAsString().equals(name) && (call.getScope().map(scope ->
                matchesType(unit, scope.toString(), "org.junit.Assert")).orElse(false)
                || !call.getScope().isPresent() && unit.getImports().stream().anyMatch(declaration ->
                declaration.isStatic() && (declaration.getNameAsString().equals("org.junit.Assert." + name)
                || declaration.isAsterisk() && declaration.getNameAsString().equals("org.junit.Assert"))));
    }

    private Expression exactMatcherValue(CompilationUnit unit, Expression value) {
        if (!value.isMethodCallExpr()) {
            return value;
        }
        MethodCallExpr call = value.asMethodCallExpr();
        boolean matcher = isMockitoCall(unit, call, "eq") || call.getScope().map(scope ->
                matchesType(unit, scope.toString(), "org.mockito.ArgumentMatchers")).orElse(false)
                || !call.getScope().isPresent() && unit.getImports().stream().anyMatch(declaration ->
                declaration.isStatic() && (declaration.getNameAsString().equals("org.mockito.ArgumentMatchers.eq")
                || declaration.isAsterisk() && declaration.getNameAsString().equals("org.mockito.ArgumentMatchers")));
        return call.getNameAsString().equals("eq") && call.getArguments().size() == 1 && matcher
                ? call.getArgument(0) : value;
    }

    private boolean matchesExpectedWrite(CompilationUnit unit, Expression value, String expected) {
        if (expected.startsWith("new BigDecimal(") && value.isObjectCreationExpr()) {
            com.github.javaparser.ast.expr.ObjectCreationExpr creation = value.asObjectCreationExpr();
            return matchesType(unit, creation.getType().asString(), "java.math.BigDecimal")
                    && !creation.getAnonymousClassBody().isPresent() && creation.getArguments().size() == 1
                    && ("new BigDecimal(" + creation.getArgument(0) + ")").equals(expected);
        }
        if (expected.equals("BigDecimal.valueOf(2)") && value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            return call.getNameAsString().equals("valueOf") && call.getArguments().size() == 1
                    && call.getArgument(0).toString().equals("2") && call.getScope().map(scope ->
                    isHttpClassReference(unit, scope, "java.math.BigDecimal")).orElse(false);
        }
        return value.toString().equals(expected);
    }

    /** 精确类型已证明旧 API 不存在，只认可同一实体正文的读取修正，不修改其它断言参数。 */
    private void normalizeStringEntityBodyAssertions(CompilationUnit previous, CompilationUnit next) {
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr original : method.findAll(MethodCallExpr.class)) {
                if (!isJupiterAssertion(previous, original, "assertEquals")
                        || original.getArguments().size() < 2 || original.getArguments().size() > 3
                        || !original.getArgument(1).isMethodCallExpr()) {
                    continue;
                }
                Expression entity = stringEntityBodyReceiver(previous, method, original.getArgument(1).asMethodCallExpr());
                if (entity == null) {
                    continue;
                }
                for (MethodDeclaration candidate : next.findAll(MethodDeclaration.class)) {
                    if (!candidate.getSignature().equals(method.getSignature()) || !isTest(next, candidate)
                            || !sameEntityBodyInputs(method, candidate, entity)) {
                        continue;
                    }
                    for (MethodCallExpr replacement : candidate.findAll(MethodCallExpr.class)) {
                        if (!isJupiterAssertion(next, replacement, "assertEquals")
                                || replacement.getArguments().size() != original.getArguments().size()
                                || !replacement.getArgument(1).isMethodCallExpr()) {
                            continue;
                        }
                        MethodCallExpr read = replacement.getArgument(1).asMethodCallExpr();
                        if (!read.getNameAsString().equals("toString") || read.getArguments().isEmpty()
                                || read.getArguments().size() > 2
                                || !read.getScope().filter(scope -> isHttpClassReference(next, scope,
                                "org.apache.http.util.EntityUtils")).isPresent()
                                || read.getArguments().size() == 2 && !isUtf8Charset(next, read.getArgument(1))
                                || !httpEntityBodyExpression(next, read.getArgument(0)).equals(entity)) {
                            continue;
                        }
                        Expression originalScope = original.getArgument(1).asMethodCallExpr().getScope().get();
                        Expression input = read.getArgument(0);
                        while (originalScope.isEnclosedExpr()) {
                            originalScope = originalScope.asEnclosedExpr().getInner();
                        }
                        while (input.isEnclosedExpr()) {
                            input = input.asEnclosedExpr().getInner();
                        }
                        if (!input.equals(originalScope) && !hasStringEntityTypeAssertion(previous, method, entity)) {
                            // 删除转换不能顺带取消原有的类型约束；已有独立类型断言仍受完整表达式保护。
                            continue;
                        }
                        MethodCallExpr normalized = replacement.clone();
                        normalized.setArgument(1, original.getArgument(1).clone());
                        if (normalized.equals(original)) {
                            replacement.setArgument(1, original.getArgument(1).clone());
                        }
                    }
                }
            }
        }
    }

    private boolean hasStringEntityTypeAssertion(CompilationUnit unit, MethodDeclaration method, Expression entity) {
        return method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                isJupiterAssertion(unit, call, "assertTrue") && !call.getArguments().isEmpty()
                        && call.getArgument(0).isInstanceOfExpr()
                        && matchesType(unit, call.getArgument(0).asInstanceOfExpr().getType().asString(),
                        "org.apache.http.entity.StringEntity")
                        && httpEntityBodyExpression(unit, call.getArgument(0).asInstanceOfExpr().getExpression()).equals(entity));
    }

    /** 保留实体表达式涉及的全部局部对象及其创建来源，不接受同名变量被换成另一份实体。 */
    private boolean sameEntityBodyInputs(MethodDeclaration previous, MethodDeclaration next, Expression entity) {
        List<com.github.javaparser.ast.expr.NameExpr> names = entity.findAll(com.github.javaparser.ast.expr.NameExpr.class);
        if (names.isEmpty()) {
            return false;
        }
        for (com.github.javaparser.ast.expr.NameExpr name : names) {
            List<com.github.javaparser.ast.body.VariableDeclarator> before = previous
                    .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name.getNameAsString()))
                    .collect(java.util.stream.Collectors.toList());
            List<com.github.javaparser.ast.body.VariableDeclarator> after = next
                    .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name.getNameAsString()))
                    .collect(java.util.stream.Collectors.toList());
            if (before.size() != 1 || after.size() != 1 || !before.get(0).getInitializer().isPresent()
                    || !before.get(0).getType().equals(after.get(0).getType())
                    || !before.get(0).getInitializer().equals(after.get(0).getInitializer())
                    || java.util.stream.Stream.of(previous, next).anyMatch(method -> method
                    .findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream().anyMatch(assignment ->
                            assignment.getTarget().equals(name)))) {
                return false;
            }
        }
        return true;
    }

    private boolean isUtf8Charset(CompilationUnit unit, Expression expression) {
        return expression.isStringLiteralExpr() && expression.asStringLiteralExpr().asString().equals("UTF-8")
                || expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getNameAsString().equals("UTF_8")
                && (isHttpClassReference(unit, expression.asFieldAccessExpr().getScope(), "org.apache.http.Consts")
                || isHttpClassReference(unit, expression.asFieldAccessExpr().getScope(), "java.nio.charset.StandardCharsets"));
    }

    /** 只有源码和本次失败共同证明裸 MIME 预期错误时，认可完整 JSON 请求头预期的精确纠正。 */
    private void normalizeJsonContentTypeAssertions(CompilationUnit previous, CompilationUnit next,
                                                     String testClass, List<String> verifyErrors, String headerSource) {
        String diagnostics = String.join("\n", verifyErrors);
        if (!diagnostics.contains(testClass)
                || !diagnostics.contains("expected: <application/json> but was: <application/json; charset=UTF-8>")
                || !hasJsonContentTypeHeader(headerSource)) {
            return;
        }
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)
                    || !(diagnostics.contains(testClass + "#" + method.getNameAsString())
                    || diagnostics.contains(testClass + "." + method.getNameAsString()))) {
                continue;
            }
            for (MethodCallExpr original : method.findAll(MethodCallExpr.class)) {
                if (!isJupiterAssertion(previous, original, "assertEquals")
                        || original.getArguments().size() < 2 || original.getArguments().size() > 3
                        || !original.getArgument(0).isStringLiteralExpr()
                        || !original.getArgument(0).asStringLiteralExpr().asString().equals("application/json")) {
                    continue;
                }
                String receiver = contentTypeRequestReceiver(previous, original.getArgument(1));
                com.github.javaparser.ast.body.VariableDeclarator before = receiver == null ? null
                        : localHttpUriRequest(previous, method, receiver);
                if (before == null || !before.getInitializer().isPresent()) {
                    continue;
                }
                for (MethodDeclaration candidate : next.findAll(MethodDeclaration.class)) {
                    if (!candidate.getSignature().equals(method.getSignature()) || !isTest(next, candidate)) {
                        continue;
                    }
                    com.github.javaparser.ast.body.VariableDeclarator after = localHttpUriRequest(next, candidate, receiver);
                    if (after == null || !after.getType().equals(before.getType())
                            || !after.getInitializer().equals(before.getInitializer())) {
                        continue;
                    }
                    for (MethodCallExpr replacement : candidate.findAll(MethodCallExpr.class)) {
                        if (!isJupiterAssertion(next, replacement, "assertEquals")
                                || replacement.getArguments().size() != original.getArguments().size()
                                || !(isJsonContentTypeValue(next, replacement.getArgument(0))
                                || replacement.getArgument(0).isStringLiteralExpr()
                                && replacement.getArgument(0).asStringLiteralExpr().asString()
                                .equals("application/json; charset=UTF-8"))
                                || !receiver.equals(contentTypeRequestReceiver(next, replacement.getArgument(1)))) {
                            continue;
                        }
                        MethodCallExpr normalized = replacement.clone();
                        normalized.setArgument(0, original.getArgument(0).clone());
                        if (normalized.equals(original)) {
                            // 仅归一化内存中的比较 AST，实际计划仍须保留完整请求头断言。
                            replacement.setArgument(0, original.getArgument(0).clone());
                        }
                    }
                }
            }
        }
    }

    private boolean hasJsonContentTypeHeader(String source) {
        if (source == null || source.trim().isEmpty()) {
            return false;
        }
        CompilationUnit unit = parse(source);
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!method.getNameAsString().equals("doPost")) {
                continue;
            }
            List<MethodCallExpr> writes = method.findAll(MethodCallExpr.class).stream().filter(call ->
                    call.getNameAsString().equals("put") && call.getArguments().size() == 2
                            && isHttpContentTypeKey(unit, call.getArgument(0)))
                    .collect(java.util.stream.Collectors.toList());
            if (writes.size() != 1 || !isJsonContentTypeValue(unit, writes.get(0).getArgument(1))
                    || !writes.get(0).getScope().filter(Expression::isNameExpr).isPresent()) {
                continue;
            }
            Expression headers = writes.get(0).getScope().get();
            // 只采用确实传入本模板 HTTP invoke 的同一份头部参数，不采用无关的常量引用。
            if (method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    call.getNameAsString().equals("invoke") && call.getArguments().size() == 5
                            && call.getArgument(2).equals(headers))) {
                return true;
            }
        }
        return false;
    }

    /** 仅认可对捕获请求完整请求头的直接读取，不认可 MIME 提取或字符串截取。 */
    private String contentTypeRequestReceiver(CompilationUnit unit, Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return null;
        }
        MethodCallExpr value = expression.asMethodCallExpr();
        if (!value.getNameAsString().equals("getValue") || !value.getArguments().isEmpty()
                || !value.getScope().filter(Expression::isMethodCallExpr).isPresent()) {
            return null;
        }
        MethodCallExpr header = value.getScope().get().asMethodCallExpr();
        return header.getNameAsString().equals("getFirstHeader") && header.getArguments().size() == 1
                && isHttpContentTypeKey(unit, header.getArgument(0))
                && header.getScope().filter(Expression::isNameExpr).isPresent()
                ? header.getScope().get().asNameExpr().getNameAsString() : null;
    }

    private boolean isHttpContentTypeKey(CompilationUnit unit, Expression expression) {
        return expression.isStringLiteralExpr() && expression.asStringLiteralExpr().asString().equals("Content-Type")
                || expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getNameAsString().equals("CONTENT_TYPE")
                && isHttpClassReference(unit, expression.asFieldAccessExpr().getScope(), "org.apache.http.protocol.HTTP");
    }

    private boolean isJsonContentTypeValue(CompilationUnit unit, Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        return call.getNameAsString().equals("toString") && call.getArguments().isEmpty()
                && call.getScope().filter(Expression::isFieldAccessExpr).map(scope ->
                scope.asFieldAccessExpr().getNameAsString().equals("APPLICATION_JSON")
                        && isHttpClassReference(unit, scope.asFieldAccessExpr().getScope(),
                        "org.apache.http.entity.ContentType")).orElse(false);
    }

    /** 同名自定义类型或变量不能冒充 Apache HTTP 常量提供方。 */
    private boolean isHttpClassReference(CompilationUnit unit, Expression scope, String qualified) {
        String name = scope.toString();
        return matchesType(unit, name, qualified)
                && unit.findAll(TypeDeclaration.class).stream().noneMatch(type -> type.getNameAsString().equals(name))
                && unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .noneMatch(variable -> variable.getNameAsString().equals(name))
                && unit.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                .noneMatch(parameter -> parameter.getNameAsString().equals(name));
    }

    /** 本次编译诊断确认类型错误时，只归一化同一局部请求对象上的显式接口转换。 */
    private void normalizeHttpRequestEntityCasts(CompilationUnit previous, CompilationUnit next,
                                                List<String> verifyErrors) {
        String diagnostics = String.join("\n", verifyErrors);
        if (!diagnostics.contains("getEntity") || !diagnostics.contains("HttpUriRequest")
                || next.findAll(TypeDeclaration.class).stream()
                .anyMatch(type -> type.getNameAsString().equals("HttpEntityEnclosingRequest"))) {
            return;
        }
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr original : method.findAll(MethodCallExpr.class)) {
                if (!original.getNameAsString().equals("getEntity") || !original.getArguments().isEmpty()
                        || !original.getScope().filter(Expression::isNameExpr).isPresent()) {
                    continue;
                }
                String receiver = original.getScope().get().asNameExpr().getNameAsString();
                com.github.javaparser.ast.body.VariableDeclarator before = localHttpUriRequest(previous, method, receiver);
                if (before == null || !before.getInitializer().isPresent()) {
                    continue;
                }
                for (MethodDeclaration candidate : next.findAll(MethodDeclaration.class)) {
                    if (!candidate.getSignature().equals(method.getSignature()) || !isTest(next, candidate)) {
                        continue;
                    }
                    com.github.javaparser.ast.body.VariableDeclarator after = localHttpUriRequest(next, candidate, receiver);
                    if (after == null || !after.getInitializer().equals(before.getInitializer())) {
                        continue;
                    }
                    for (MethodCallExpr replacement : candidate.findAll(MethodCallExpr.class)) {
                        if (!replacement.getNameAsString().equals("getEntity") || !replacement.getArguments().isEmpty()
                                || !replacement.getScope().isPresent()) {
                            continue;
                        }
                        Expression scope = replacement.getScope().get();
                        while (scope.isEnclosedExpr()) {
                            scope = scope.asEnclosedExpr().getInner();
                        }
                        if (scope.isCastExpr() && matchesType(next, scope.asCastExpr().getType().asString(),
                                "org.apache.http.HttpEntityEnclosingRequest")
                                && scope.asCastExpr().getExpression().equals(original.getScope().get())) {
                            com.github.javaparser.ast.Node parent = replacement.getParentNode().orElse(null);
                            if (parent instanceof com.github.javaparser.ast.expr.ConditionalExpr) {
                                com.github.javaparser.ast.expr.ConditionalExpr conditional =
                                        (com.github.javaparser.ast.expr.ConditionalExpr) parent;
                                Expression condition = conditional.getCondition();
                                if (conditional.getThenExpr() == replacement && conditional.getElseExpr().isNullLiteralExpr()
                                        && condition.isInstanceOfExpr()
                                        && condition.asInstanceOfExpr().getExpression().equals(original.getScope().get())
                                        && matchesType(next, condition.asInstanceOfExpr().getType().asString(),
                                        "org.apache.http.HttpEntityEnclosingRequest")) {
                                    conditional.replace(original.clone());
                                }
                            } else {
                                replacement.setScope(original.getScope().get().clone());
                            }
                        }
                    }
                }
            }
        }
    }

    /** 只有真实业务调用 builder.build() 时，认可同一 verify 中的请求参数类型纠正。 */
    private void normalizeHttpExecuteMatchers(CompilationUnit previous, CompilationUnit next, CompilationUnit business) {
        boolean builtRequest = business.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                method.findAll(MethodCallExpr.class).stream().anyMatch(call -> {
                    if (!call.getNameAsString().equals("execute") || call.getArguments().size() != 1
                            || !isHttpClientReceiver(business, method, call) || !call.getArgument(0).isMethodCallExpr()) {
                        return false;
                    }
                    MethodCallExpr build = call.getArgument(0).asMethodCallExpr();
                    return build.getNameAsString().equals("build") && build.getArguments().isEmpty()
                            && build.getScope().filter(scope -> scope.isNameExpr()).isPresent()
                            && method.getParameters().stream().anyMatch(parameter ->
                            parameter.getNameAsString().equals(build.getScope().get().asNameExpr().getNameAsString())
                                    && matchesType(business, parameter.getType().asString(),
                                    "org.apache.http.client.methods.RequestBuilder"));
                }));
        if (!builtRequest) {
            return;
        }
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr original : method.findAll(MethodCallExpr.class)) {
                if (!original.getNameAsString().equals("execute") || original.getArguments().size() != 1
                        || !original.getScope().filter(scope -> scope.isMethodCallExpr()
                        && isMockitoCall(previous, scope.asMethodCallExpr(), "verify")).isPresent()
                        || !isHttpClientReceiver(previous, method, original)
                        || !isTypedAny(previous, original.getArgument(0), "org.apache.http.client.methods.RequestBuilder")) {
                    continue;
                }
                for (MethodDeclaration candidate : next.findAll(MethodDeclaration.class)) {
                    if (!candidate.getSignature().equals(method.getSignature()) || !isTest(next, candidate)) {
                        continue;
                    }
                    for (MethodCallExpr replacement : candidate.findAll(MethodCallExpr.class)) {
                        if (!replacement.getNameAsString().equals("execute") || replacement.getArguments().size() != 1
                                || !isHttpClientReceiver(next, candidate, replacement)
                                || !isTypedAny(next, replacement.getArgument(0), "org.apache.http.client.methods.HttpUriRequest")) {
                            continue;
                        }
                        MethodCallExpr normalized = replacement.clone();
                        normalized.setArgument(0, original.getArgument(0).clone());
                        if (normalized.equals(original)) {
                            replacement.setArgument(0, original.getArgument(0).clone());
                        }
                    }
                }
            }
        }
    }

    /** 仅在关闭分支确实先返回时，将零构造验证按原验证意图比较；不修改实际计划源码。 */
    private void normalizeDisabledConstructionAssertions(CompilationUnit previous, CompilationUnit next,
                                                           CompilationUnit business) {
        MethodDeclaration invoke = business.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("ahiInvoke") && method.getBody().isPresent())
                .findFirst().orElse(null);
        boolean nullableSwitch = business.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                method.getNameAsString().equals("verifyInvokeCondition")
                        && method.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream().anyMatch(branch ->
                        branch.getCondition().toString().equals("!Boolean.TRUE.equals(readConfig.getAhiHttpInvokeEnable())")
                                && branch.getThenStmt().isBlockStmt()
                                && branch.getThenStmt().asBlockStmt().getStatements().size() == 1
                                && branch.getThenStmt().asBlockStmt().getStatement(0).isReturnStmt()
                                && branch.getThenStmt().asBlockStmt().getStatement(0).asReturnStmt().getExpression()
                                .map(value -> value.toString().equals("false")).orElse(false)));
        if (invoke == null || !nullableSwitch) {
            return;
        }
        String businessType = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                + business.getType(0).getNameAsString();
        boolean realFixture = java.util.Arrays.asList(previous, next).stream().allMatch(unit ->
                unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                        .flatMap(field -> field.getVariables().stream()).anyMatch(variable ->
                        variable.getNameAsString().equals("handler") && matchesType(unit, variable.getType().asString(), businessType))
                        && unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                        .flatMap(field -> field.getVariables().stream()).anyMatch(variable ->
                        variable.getNameAsString().equals("readConfig") && variable.getType().asString().equals("ReadConfig")));
        if (!realFixture) {
            return;
        }
        List<com.github.javaparser.ast.stmt.Statement> statements = invoke.getBody().get().getStatements();
        boolean earlyReturn = false;
        boolean conditionDeclared = false;
        for (com.github.javaparser.ast.stmt.Statement statement : statements) {
            if (!statement.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).isEmpty()) {
                break;
            }
            if (statement.isIfStmt() && statement.asIfStmt().getCondition().toString().equals("!isAhiInvoke")
                    && statement.asIfStmt().getThenStmt().isBlockStmt()
                    && statement.asIfStmt().getThenStmt().asBlockStmt().getStatements().size() == 2
                    && statement.asIfStmt().getThenStmt().asBlockStmt().getStatement(0).isExpressionStmt()
                    && statement.asIfStmt().getThenStmt().asBlockStmt().getStatement(0).asExpressionStmt()
                    .getExpression().toString().equals("eventData.putDataAndOriginData(\"ahiResultAvailable\", \"2\")")
                    && statement.asIfStmt().getThenStmt().asBlockStmt().getStatement(1).isReturnStmt()
                    && !statement.asIfStmt().getThenStmt().asBlockStmt().getStatement(1).asReturnStmt().getExpression().isPresent()) {
                earlyReturn = conditionDeclared;
                break;
            }
            conditionDeclared |= statement.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .anyMatch(variable ->
                        variable.getNameAsString().equals("isAhiInvoke") && variable.getInitializer()
                                .map(value -> value.toString().equals("verifyInvokeCondition(eventFields)")).orElse(false));
        }
        if (!earlyReturn) {
            return;
        }
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method) || !hasDisabledInvocation(method)) {
                continue;
            }
            for (MethodDeclaration candidate : next.findAll(MethodDeclaration.class)) {
                if (!candidate.getSignature().equals(method.getSignature()) || !isTest(next, candidate)
                        || !candidate.getBody().isPresent() || !hasDisabledInvocation(candidate)) {
                    continue;
                }
                for (com.github.javaparser.ast.body.VariableDeclarator field : previous.findAll(
                        com.github.javaparser.ast.body.VariableDeclarator.class)) {
                    if (!(field.getParentNode().orElse(null) instanceof com.github.javaparser.ast.body.FieldDeclaration)
                            || !field.getType().asString().equals("MockedConstruction<AhiHttpInvokerHandler>")) {
                        continue;
                    }
                    String handle = field.getNameAsString();
                    boolean sameField = next.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                            .filter(variable -> variable.getNameAsString().equals(handle))
                            .collect(java.util.stream.Collectors.toList()).size() == 1
                            && next.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                            .anyMatch(variable -> variable.getNameAsString().equals(handle)
                                    && variable.getType().equals(field.getType())
                                    && variable.getParentNode().orElse(null) instanceof
                                    com.github.javaparser.ast.body.FieldDeclaration);
                    if (!sameField || method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                            .anyMatch(variable -> variable.getNameAsString().equals(handle))) {
                        continue;
                    }
                    boolean registered = java.util.Arrays.asList(previous, next).stream().allMatch(unit ->
                            unit.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream().anyMatch(assignment ->
                            assignment.getTarget().toString().equals(handle) && assignment.getValue().isMethodCallExpr()
                                    && isMockitoCall(unit, assignment.getValue().asMethodCallExpr(), "mockConstruction")
                                    && !assignment.getValue().asMethodCallExpr().getArguments().isEmpty()
                                    && assignment.getValue().asMethodCallExpr().getArgument(0).toString()
                                    .equals("AhiHttpInvokerHandler.class")));
                    if (!registered) {
                        continue;
                    }
                    List<MethodCallExpr> wrongCounts = method.findAll(MethodCallExpr.class).stream().filter(call ->
                            isJupiterAssertion(previous, call, "assertEquals") && call.getArguments().size() == 2
                                    && call.getArgument(0).toString().equals("1")
                                    && call.getArgument(1).toString().equals(handle + ".constructed().size()"))
                            .collect(java.util.stream.Collectors.toList());
                    List<MethodCallExpr> zeroCalls = method.findAll(MethodCallExpr.class).stream().filter(call -> {
                        if (!call.getNameAsString().equals("doPost") || !call.getScope().isPresent()
                                || !call.getScope().get().isMethodCallExpr()) {
                            return false;
                        }
                        MethodCallExpr verify = call.getScope().get().asMethodCallExpr();
                        return isMockitoCall(previous, verify, "verify") && verify.getArguments().size() == 2
                                && verify.getArgument(0).toString().equals(handle + ".constructed().get(0)")
                                && verify.getArgument(1).isMethodCallExpr()
                                && isMockitoCall(previous, verify.getArgument(1).asMethodCallExpr(), "times")
                                && verify.getArgument(1).asMethodCallExpr().getArguments().size() == 1
                                && verify.getArgument(1).asMethodCallExpr().getArgument(0).toString().equals("0");
                    }).collect(java.util.stream.Collectors.toList());
                    List<MethodCallExpr> emptyChecks = candidate.findAll(MethodCallExpr.class).stream().filter(call ->
                            isJupiterAssertion(next, call, "assertTrue") && call.getArguments().size() == 1
                                    && call.getArgument(0).toString().equals(handle + ".constructed().isEmpty()")
                                    || isJupiterAssertion(next, call, "assertEquals") && call.getArguments().size() == 2
                                    && call.getArgument(0).toString().equals("0")
                                    && call.getArgument(1).toString().equals(handle + ".constructed().size()"))
                            .collect(java.util.stream.Collectors.toList());
                    if (wrongCounts.size() == 1 && zeroCalls.size() == 1 && emptyChecks.size() == 1
                            && candidate.findAll(MethodCallExpr.class).stream().noneMatch(call ->
                            call.equals(wrongCounts.get(0)) || call.equals(zeroCalls.get(0)))) {
                        emptyChecks.get(0).replace(wrongCounts.get(0).clone());
                        candidate.getBody().get().addStatement(new com.github.javaparser.ast.stmt.ExpressionStmt(zeroCalls.get(0).clone()));
                    }
                }
            }
        }
    }

    private boolean hasDisabledInvocation(MethodDeclaration method) {
        if (!method.getBody().isPresent() || !method.getParameters().isEmpty()
                || method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .anyMatch(variable -> java.util.Arrays.asList("handler", "readConfig", "ctx", "eventData")
                        .contains(variable.getNameAsString()))
                || method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .anyMatch(assignment -> java.util.Arrays.asList("handler", "readConfig", "ctx", "eventData")
                        .contains(assignment.getTarget().toString()))) {
            return false;
        }
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
        long setters = calls.stream().filter(call -> call.getNameAsString().equals("setAhiHttpInvokeEnable")).count();
        return setters == 1 && calls.stream().anyMatch(setter -> java.util.Arrays.asList(
                "readConfig.setAhiHttpInvokeEnable(false)", "readConfig.setAhiHttpInvokeEnable(Boolean.FALSE)",
                "readConfig.setAhiHttpInvokeEnable(null)").contains(setter.toString())
                && setter.getParentNode().flatMap(parent -> parent.getParentNode())
                .filter(parent -> parent == method.getBody().get()).isPresent()
                && calls.stream().anyMatch(invoke -> invoke.toString().equals("handler.ahiInvoke(ctx, eventData)")
                && invoke.getParentNode().flatMap(parent -> parent.getParentNode())
                .filter(parent -> parent == method.getBody().get()).isPresent()
                && setter.getBegin().isPresent() && invoke.getBegin().isPresent()
                && setter.getBegin().get().isBefore(invoke.getBegin().get())))
                && method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                .noneMatch(creation -> creation.getType().asString().equals("AhiHttpInvokerHandler"));
    }

    private boolean isMockitoCall(CompilationUnit unit, MethodCallExpr call, String name) {
        return call.getNameAsString().equals(name) && (call.getScope()
                .map(scope -> matchesType(unit, scope.toString(), "org.mockito.Mockito")).orElse(false)
                || !call.getScope().isPresent() && unit.getImports().stream().anyMatch(declaration ->
                declaration.isStatic() && (declaration.getNameAsString().equals("org.mockito.Mockito." + name)
                        || declaration.isAsterisk() && declaration.getNameAsString().equals("org.mockito.Mockito"))));
    }

    /** Jackson 协议键不决定 Lombok 的调试文本；只检查直接可证明的实体局部变量。 */
    private void validateLombokToStringAssertions(CompilationUnit unit, CompilationUnit business, List<String> errors) {
        Map<String, String> properties = lombokToStringProperties(business);
        if (properties.isEmpty()) {
            return;
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!isTest(unit, method)) {
                continue;
            }
            for (MethodCallExpr assertion : method.findAll(MethodCallExpr.class)) {
                String field = correctedLombokToStringField(unit, method, assertion, business, properties);
                if (field != null) {
                    errors.add("Lombok toString 字段名错误: " + method.getNameAsString()
                            + "；@JsonProperty 只影响 JSON 序列化，默认 toString 使用 Java 字段名 " + field
                            + "。保留该 assertTrue、原对象及 contains，仅将此处协议键改为真实 Java 字段名；"
                            + "其它 JSON 断言仍使用协议键，不改生产注解或 toString。");
                }
            }
        }
    }

    /** 只归一化完全相同断言中的字段名纠正，断言数量、接收者和其它表达式仍受原保护。 */
    private void normalizeLombokToStringAssertions(CompilationUnit previous, CompilationUnit next,
                                                   CompilationUnit business) {
        Map<String, String> properties = lombokToStringProperties(business);
        if (properties.isEmpty()) {
            return;
        }
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr assertion : method.findAll(MethodCallExpr.class)) {
                String field = correctedLombokToStringField(previous, method, assertion, business, properties);
                if (field == null) {
                    continue;
                }
                MethodCallExpr corrected = assertion.clone();
                corrected.getArgument(0).asMethodCallExpr().setArgument(0,
                        new com.github.javaparser.ast.expr.StringLiteralExpr(field));
                next.findAll(MethodDeclaration.class).stream()
                        .filter(candidate -> candidate.getSignature().equals(method.getSignature()) && isTest(next, candidate))
                        .flatMap(candidate -> candidate.findAll(MethodCallExpr.class).stream())
                        .filter(candidate -> isJupiterAssertion(next, candidate, "assertTrue") && candidate.equals(corrected))
                        .forEach(candidate -> candidate.setArgument(0, assertion.getArgument(0).clone()));
            }
        }
    }

    private Map<String, String> lombokToStringProperties(CompilationUnit business) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (business.getTypes().size() != 1 || !business.getType(0).isClassOrInterfaceDeclaration()) {
            return properties;
        }
        ClassOrInterfaceDeclaration type = business.getType(0).asClassOrInterfaceDeclaration();
        boolean generated = type.getAnnotations().stream().anyMatch(annotation ->
                matchesType(business, annotation.getNameAsString(), "lombok.Data")
                        || matchesType(business, annotation.getNameAsString(), "lombok.ToString"));
        boolean customized = type.findAll(AnnotationExpr.class).stream().anyMatch(annotation ->
                matchesType(business, annotation.getNameAsString(), "lombok.ToString") && !annotation.isMarkerAnnotationExpr()
                        || annotation.getNameAsString().contains("ToString.")
                        || matchesType(business, annotation.getNameAsString(), "lombok.ToString.Include")
                        || matchesType(business, annotation.getNameAsString(), "lombok.ToString.Exclude"));
        if (!generated || customized || !type.getExtendedTypes().isEmpty()
                || type.getMethods().stream().anyMatch(method -> method.getNameAsString().equals("toString")
                && method.getParameters().isEmpty())) {
            return properties;
        }
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic() || field.isTransient() || field.getVariables().size() != 1) {
                continue;
            }
            for (AnnotationExpr annotation : field.getAnnotations()) {
                if (!matchesType(business, annotation.getNameAsString(), "com.fasterxml.jackson.annotation.JsonProperty")) {
                    continue;
                }
                Expression value = annotation.isSingleMemberAnnotationExpr()
                        ? annotation.asSingleMemberAnnotationExpr().getMemberValue()
                        : annotation.isNormalAnnotationExpr() ? annotation.asNormalAnnotationExpr().getPairs().stream()
                        .filter(pair -> pair.getNameAsString().equals("value")).map(pair -> pair.getValue())
                        .findFirst().orElse(null) : null;
                if (value == null || !value.isStringLiteralExpr()) {
                    continue;
                }
                String property = value.asStringLiteralExpr().asString();
                String name = field.getVariable(0).getNameAsString();
                if (!property.isEmpty() && !property.equals(name) && !name.startsWith("$")) {
                    if (properties.containsKey(property)) {
                        return java.util.Collections.emptyMap();
                    }
                    properties.put(property, name);
                }
            }
        }
        return properties;
    }

    private String correctedLombokToStringField(CompilationUnit unit, MethodDeclaration method,
                                                MethodCallExpr assertion, CompilationUnit business,
                                                Map<String, String> properties) {
        if (!isJupiterAssertion(unit, assertion, "assertTrue") || assertion.getArguments().size() != 1
                || !assertion.getArgument(0).isMethodCallExpr()) {
            return null;
        }
        MethodCallExpr contains = assertion.getArgument(0).asMethodCallExpr();
        if (!contains.getNameAsString().equals("contains") || contains.getArguments().size() != 1
                || !contains.getArgument(0).isStringLiteralExpr()
                || !contains.getScope().filter(Expression::isMethodCallExpr).isPresent()) {
            return null;
        }
        String field = properties.get(contains.getArgument(0).asStringLiteralExpr().asString());
        MethodCallExpr text = contains.getScope().get().asMethodCallExpr();
        if (field == null || !text.getNameAsString().equals("toString") || !text.getArguments().isEmpty()
                || !text.getScope().filter(Expression::isNameExpr).isPresent()) {
            return null;
        }
        String receiver = text.getScope().get().asNameExpr().getNameAsString();
        String owner = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                + business.getType(0).getNameAsString();
        List<com.github.javaparser.ast.body.VariableDeclarator> variables = method
                .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(receiver))
                .collect(java.util.stream.Collectors.toList());
        if (variables.size() != 1 || !matchesType(unit, variables.get(0).getType().asString(), owner)
                || method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().toString().equals(receiver))) {
            return null;
        }
        boolean knownInstance = variables.get(0).getInitializer().map(initializer -> {
            if (initializer.isObjectCreationExpr()) {
                return matchesType(unit, initializer.asObjectCreationExpr().getType().asString(), owner)
                        && !initializer.asObjectCreationExpr().getAnonymousClassBody().isPresent();
            }
            if (!initializer.isMethodCallExpr()) {
                return false;
            }
            MethodCallExpr read = initializer.asMethodCallExpr();
            if (!read.getNameAsString().equals("readValue") || read.getArguments().size() != 2
                    || !read.getArgument(1).isClassExpr()
                    || !matchesType(unit, read.getArgument(1).asClassExpr().getType().asString(), owner)
                    || !read.getScope().filter(Expression::isNameExpr).isPresent()) {
                return false;
            }
            String mapper = read.getScope().get().asNameExpr().getNameAsString();
            List<String> mapperTypes = method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(mapper))
                    .map(variable -> variable.getType().asString()).collect(java.util.stream.Collectors.toList());
            if (mapperTypes.isEmpty()) {
                method.findAncestor(ClassOrInterfaceDeclaration.class).flatMap(type -> type.getFieldByName(mapper))
                        .ifPresent(declaration -> declaration.getVariables().stream()
                                .filter(variable -> variable.getNameAsString().equals(mapper))
                                .forEach(variable -> mapperTypes.add(variable.getType().asString())));
            }
            return mapperTypes.size() == 1 && matchesType(unit, mapperTypes.get(0),
                    "com.fasterxml.jackson.databind.ObjectMapper");
        }).orElse(false);
        return knownInstance ? field : null;
    }

    /** 仅纠正由真实请求实体注解证明的键名，保留实际值路径、预期值及断言类型。 */
    private void normalizeRequestJsonAssertions(CompilationUnit previous, CompilationUnit next, String businessSource,
                                                 Map<String, String> properties) {
        if (properties.isEmpty()) {
            return;
        }
        CompilationUnit business = parse(businessSource);
        boolean serializer = business.getTypes().stream().anyMatch(type ->
                "AhiHttpInvokerHandler".equals(type.getNameAsString()))
                && business.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                "buildRequestBody".equals(method.getNameAsString()) && method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> "writeValueAsString".equals(call.getNameAsString())));
        if (!serializer) {
            return;
        }
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr assertion : method.findAll(MethodCallExpr.class)) {
                if (!isJupiterAssertion(previous, assertion, "assertEquals") || assertion.getArguments().size() != 2) {
                    continue;
                }
                MethodCallExpr corrected = assertion.clone();
                for (MethodCallExpr read : corrected.getArgument(1).findAll(MethodCallExpr.class)) {
                    if (!java.util.Arrays.asList("get", "getAsJsonObject").contains(read.getNameAsString())
                            || read.getArguments().size() != 1 || !read.getArgument(0).isStringLiteralExpr()
                            || !read.getScope().isPresent()
                            || !read.getParentNode().filter(parent -> parent instanceof MethodCallExpr
                            && java.util.Arrays.asList("getAsString", "getAsBoolean", "getAsInt", "getAsFloat")
                            .contains(((MethodCallExpr) parent).getNameAsString())
                            && ((MethodCallExpr) parent).getScope().filter(scope -> scope == read).isPresent()).isPresent()
                            || !isRequestJson(previous, method, read.getScope().get(), 0)) {
                        continue;
                    }
                    String original = read.getArgument(0).asStringLiteralExpr().asString();
                    String property = properties.get(original.replace("_", "").toLowerCase(java.util.Locale.ROOT));
                    if (property != null) {
                        read.setArgument(0, new com.github.javaparser.ast.expr.StringLiteralExpr(property));
                    }
                }
                if (!corrected.equals(assertion)) {
                    next.findAll(MethodDeclaration.class).stream()
                            .filter(candidate -> candidate.getSignature().equals(method.getSignature()) && isTest(next, candidate))
                            .flatMap(candidate -> candidate.findAll(MethodCallExpr.class).stream())
                            .filter(candidate -> isJupiterAssertion(next, candidate, "assertEquals") && candidate.equals(corrected))
                            .forEach(candidate -> candidate.setArgument(1, assertion.getArgument(1).clone()));
                }
            }
        }
    }

    private boolean isRequestJson(CompilationUnit unit, MethodDeclaration method,
                                  com.github.javaparser.ast.expr.Expression expression, int depth) {
        if (depth > 10) {
            return false;
        }
        if (expression.isNameExpr()) {
            String name = expression.asNameExpr().getNameAsString();
            List<com.github.javaparser.ast.body.VariableDeclarator> variables = method
                    .findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(variable -> name.equals(variable.getNameAsString()))
                    .collect(java.util.stream.Collectors.toList());
            boolean reassigned = method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                    .anyMatch(assignment -> name.equals(assignment.getTarget().toString()));
            return !reassigned && variables.size() == 1 && variables.get(0).getInitializer()
                    .map(value -> isRequestJson(unit, method, value, depth + 1)).orElse(false);
        }
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        if ("buildRequestBody".equals(call.getNameAsString()) && call.getArguments().size() == 2
                && call.getScope().filter(scope -> scope.isNameExpr()).isPresent()) {
            String receiver = call.getScope().get().asNameExpr().getNameAsString();
            return method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .anyMatch(variable -> receiver.equals(variable.getNameAsString())
                            && variable.getType().asString().endsWith("AhiHttpInvokerHandler")
                            && variable.getInitializer().filter(value -> value.isObjectCreationExpr()
                            && !value.asObjectCreationExpr().getAnonymousClassBody().isPresent()).isPresent());
        }
        if ("parseString".equals(call.getNameAsString()) && call.getArguments().size() == 1
                && call.getScope().map(scope -> matchesType(unit, scope.toString(), "com.google.gson.JsonParser"))
                .orElse(false)) {
            return isRequestJson(unit, method, call.getArgument(0), depth + 1);
        }
        return "getAsJsonObject".equals(call.getNameAsString()) && call.getArguments().size() <= 1
                && call.getScope().map(scope -> isRequestJson(unit, method, scope, depth + 1)).orElse(false);
    }

    private boolean isImpossibleBooleanStringAssertion(CompilationUnit unit, MethodCallExpr call) {
        if (!isJupiterAssertion(unit, call, "assertEquals") || call.getArguments().size() != 2
                || !call.getArgument(0).isStringLiteralExpr() || !call.getArgument(1).isMethodCallExpr()) {
            return false;
        }
        String expected = call.getArgument(0).asStringLiteralExpr().asString();
        MethodCallExpr actual = call.getArgument(1).asMethodCallExpr();
        if (expected.equals("true") || expected.equals("false") || !actual.getNameAsString().equals("valueOf")
                || !actual.getScope().map(scope -> matchesType(unit, scope.toString(), "java.lang.String")
                || scope.toString().equals("String")).orElse(false) || actual.getArguments().size() != 1
                || !actual.getArgument(0).isBinaryExpr()) {
            return false;
        }
        return java.util.Arrays.asList("==", "!=", ">=", "<=", ">", "<", "&&", "||")
                .contains(actual.getArgument(0).asBinaryExpr().getOperator().asString());
    }

    /** 只允许将已证明必败的字符串/布尔比较改为真实 EventData 写回验证，不放宽正常断言。 */
    private void addImpossibleAssertionCorrections(CompilationUnit previous, CompilationUnit next,
                                                     String businessSource, Map<String, Integer> corrections) {
        CompilationUnit business = parse(businessSource);
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            boolean replacement = next.findAll(MethodDeclaration.class).stream()
                    .filter(candidate -> candidate.getSignature().equals(method.getSignature()) && isTest(next, candidate))
                    .filter(candidate -> candidate.findAll(MethodDeclaration.class).stream()
                            .noneMatch(nested -> "ahiInvoke".equals(nested.getNameAsString())))
                    .filter(candidate -> candidate.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                            .anyMatch(variable -> variable.getInitializer().filter(value -> value.isObjectCreationExpr()
                                    && !value.asObjectCreationExpr().getAnonymousClassBody().isPresent()
                                    && business.getTypes().stream().anyMatch(type ->
                                    matchesType(next, value.asObjectCreationExpr().getType().asString(),
                                    business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                                            + type.getNameAsString()))).isPresent()
                                    && candidate.findAll(MethodCallExpr.class).stream().anyMatch(invocation ->
                                    java.util.Arrays.asList("handle", "ahiInvoke").contains(invocation.getNameAsString())
                                            && invocation.getScope().map(scope -> scope.toString()
                                            .equals(variable.getNameAsString())).orElse(false))))
                    .flatMap(candidate -> candidate.findAll(MethodCallExpr.class).stream()).anyMatch(call -> {
                        if (!call.getNameAsString().equals("putDataAndOriginData") || call.getArguments().size() != 2
                                || !call.getArgument(0).isStringLiteralExpr() || !call.getScope().isPresent()
                                || !"ahiResultAvailable".equals(call.getArgument(0).asStringLiteralExpr().asString())
                                || !call.getArgument(1).isStringLiteralExpr()
                                || !"1".equals(call.getArgument(1).asStringLiteralExpr().asString())
                                || !call.getScope().get().isMethodCallExpr()) {
                            return false;
                        }
                        MethodCallExpr verify = call.getScope().get().asMethodCallExpr();
                        return verify.getNameAsString().equals("verify") && verify.getArguments().size() == 1
                                && !verify.getScope().isPresent() && verify.getArgument(0).isNameExpr()
                                && next.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                                .flatMap(field -> field.getVariables().stream()).anyMatch(variable ->
                                variable.getNameAsString().equals(verify.getArgument(0).asNameExpr().getNameAsString())
                                        && matchesType(next, variable.getType().asString(),
                                        "com.cmbchina.rces.executor.common.EventData"))
                                && !method.findAll(MethodCallExpr.class).contains(call)
                                && (next.getImports().stream().anyMatch(declaration -> declaration.isStatic()
                                && (declaration.getNameAsString().equals("org.mockito.Mockito.verify")
                                || declaration.isAsterisk() && declaration.getNameAsString().equals("org.mockito.Mockito"))))
                                && business.findAll(MethodCallExpr.class).stream().anyMatch(write ->
                                write.getNameAsString().equals(call.getNameAsString()) && write.getArguments().size() == 2
                                        && write.getArgument(0).equals(call.getArgument(0)));
                    });
            List<MethodCallExpr> invalid = method.findAll(MethodCallExpr.class).stream()
                    .filter(call -> isImpossibleBooleanStringAssertion(previous, call))
                    .collect(java.util.stream.Collectors.toList());
            if (replacement && invalid.size() == 1) {
                corrections.put(method.getSignature().asString() + ":" + invalid.get(0), 1);
            }
        }
    }

    /**
     * 仅在本次编译诊断及真实 private static 声明共同证明时，认可同字段的只读反射等价替换。
     * 只归一化用于比较的 AST，不修改测试源码；预期值、方法、断言类型和其它参数仍必须完全一致。
     */
    private void normalizePrivateFieldAssertions(CompilationUnit previous, CompilationUnit next,
                                                String businessSource, String testClass,
                                                List<String> verifyErrors) {
        CompilationUnit business = parse(businessSource);
        String testFile = testClass.substring(testClass.lastIndexOf('.') + 1) + ".java";
        for (ClassOrInterfaceDeclaration type : business.findAll(ClassOrInterfaceDeclaration.class)) {
            // 嵌套类的二进制名和可见性无法仅凭简单类型匹配可靠判断，交给原门禁处理。
            if (type.getParentNode().filter(parent -> parent instanceof CompilationUnit).isPresent()) {
                String owner = business.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("")
                        + type.getNameAsString();
                for (com.github.javaparser.ast.body.FieldDeclaration declaration : type.getFields()) {
                    if (!declaration.isPrivate() || !declaration.isStatic()) {
                        continue;
                    }
                    for (com.github.javaparser.ast.body.VariableDeclarator variable : declaration.getVariables()) {
                        String field = variable.getNameAsString();
                        boolean diagnosed = verifyErrors.stream().flatMap(error ->
                                java.util.Arrays.stream(error.split("\\R"))).anyMatch(line ->
                                line.contains(testFile) && line.contains(owner) && line.contains(field)
                                        && line.contains("private"));
                        if (diagnosed) {
                            normalizePrivateFieldAssertion(previous, next, owner, field);
                        }
                    }
                }
            }
        }
    }

    private void normalizePrivateFieldAssertion(CompilationUnit previous, CompilationUnit next,
                                               String owner, String field) {
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            if (!isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr assertion : method.findAll(MethodCallExpr.class)) {
                if (!isJupiterAssertion(previous, assertion, "assertSame") || assertion.getArguments().size() != 2
                        || !assertion.getArgument(1).isFieldAccessExpr()) {
                    continue;
                }
                com.github.javaparser.ast.expr.FieldAccessExpr original = assertion.getArgument(1).asFieldAccessExpr();
                if (!original.getNameAsString().equals(field) || !matchesType(previous, original.getScope().toString(), owner)) {
                    continue;
                }
                for (MethodDeclaration candidate : next.findAll(MethodDeclaration.class)) {
                    if (!candidate.getSignature().equals(method.getSignature()) || !isTest(next, candidate)) {
                        continue;
                    }
                    for (MethodCallExpr replacement : candidate.findAll(MethodCallExpr.class)) {
                        if (!isJupiterAssertion(next, replacement, "assertSame") || replacement.getArguments().size() != 2
                                || !replacement.getArgument(1).isMethodCallExpr()) {
                            continue;
                        }
                        MethodCallExpr read = replacement.getArgument(1).asMethodCallExpr();
                        if (!isPrivateStaticFieldRead(next, read, owner, field)) {
                            continue;
                        }
                        MethodCallExpr normalized = replacement.clone();
                        normalized.setArgument(1, original.clone());
                        if (normalized.equals(assertion)) {
                            replacement.setArgument(1, original.clone());
                        }
                    }
                }
            }
        }
    }

    private boolean isPrivateStaticFieldRead(CompilationUnit unit, MethodCallExpr read, String owner, String field) {
        String utility = "org.springframework.test.util.ReflectionTestUtils";
        return read.getNameAsString().equals("getField") && read.getArguments().size() == 2
                && read.getScope().map(scope -> matchesType(unit, scope.toString(), utility)).orElse(false)
                && read.getArgument(0).isClassExpr()
                && matchesType(unit, read.getArgument(0).asClassExpr().getType().asString(), owner)
                && read.getArgument(1).isStringLiteralExpr()
                && read.getArgument(1).asStringLiteralExpr().asString().equals(field)
                && unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .noneMatch(type -> type.getNameAsString().equals("ReflectionTestUtils"))
                && unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .noneMatch(variable -> variable.getNameAsString().equals("ReflectionTestUtils"))
                && unit.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                .noneMatch(parameter -> parameter.getNameAsString().equals("ReflectionTestUtils"));
    }

    /** 仅允许本次失败证据与源码异常契约共同证明的、不改变被测调用的异常预期纠正。 */
    private Map<String, Integer> exceptionAssertionCorrections(CompilationUnit previous, CompilationUnit next,
                                                              String businessSource, String testClass,
                                                              List<String> verifyErrors) {
        Map<String, String> exceptions = new LinkedHashMap<>();
        String failedMethod = null;
        for (String error : verifyErrors) {
            if (error.startsWith("测试失败: ")) {
                String prefix = "测试失败: " + testClass + "#";
                failedMethod = error.startsWith(prefix) ? error.substring(prefix.length()) : null;
            } else if (!error.startsWith("异常根因: ")) {
                failedMethod = null;
            }
            java.util.regex.Matcher exception = java.util.regex.Pattern
                    .compile("Unexpected exception thrown: ([\\w.$]+):").matcher(error);
            if (failedMethod != null && error.startsWith("异常根因: ") && exception.find()) {
                exceptions.put(failedMethod, exception.group(1));
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        if (exceptions.isEmpty()) {
            return result;
        }
        CompilationUnit business = parse(businessSource);
        for (MethodDeclaration method : previous.findAll(MethodDeclaration.class)) {
            String exception = exceptions.get(method.getNameAsString());
            if (exception == null || !isTest(previous, method)) {
                continue;
            }
            for (MethodCallExpr assertion : method.findAll(MethodCallExpr.class)) {
                if (!isJupiterAssertion(previous, assertion, "assertDoesNotThrow")
                        || assertion.getArguments().size() != 1 || !assertion.getArgument(0).isLambdaExpr()) {
                    continue;
                }
                com.github.javaparser.ast.stmt.Statement body = assertion.getArgument(0).asLambdaExpr().getBody();
                if (!body.isExpressionStmt() || !body.asExpressionStmt().getExpression().isMethodCallExpr()) {
                    continue;
                }
                MethodCallExpr invocation = body.asExpressionStmt().getExpression().asMethodCallExpr();
                if (!invocation.getScope().isPresent() || !invocation.getScope().get().isNameExpr()) {
                    continue;
                }
                String receiver = invocation.getScope().get().asNameExpr().getNameAsString();
                boolean businessReceiver = method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                        .stream().anyMatch(variable -> variable.getNameAsString().equals(receiver)
                                && business.getTypes().stream().anyMatch(type -> matchesType(previous,
                                variable.getType().asString(), business.getPackageDeclaration()
                                        .map(pkg -> pkg.getNameAsString() + ".").orElse("") + type.getNameAsString())));
                boolean declared = business.findAll(MethodDeclaration.class).stream().anyMatch(target ->
                        target.getNameAsString().equals(invocation.getNameAsString())
                                && target.getParameters().size() == invocation.getArguments().size()
                                && target.getThrownExceptions().stream().anyMatch(type ->
                                matchesType(business, type.asString(), exception))
                                && target.findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).stream()
                                .anyMatch(statement -> statement.getExpression().isObjectCreationExpr()
                                        && matchesType(business, statement.getExpression().asObjectCreationExpr()
                                        .getType().asString(), exception)));
                if (!businessReceiver || !declared) {
                    continue;
                }
                long replacements = next.findAll(MethodDeclaration.class).stream()
                        .filter(candidate -> candidate.getSignature().equals(method.getSignature()) && isTest(next, candidate))
                        .flatMap(candidate -> candidate.findAll(MethodCallExpr.class).stream())
                        .filter(candidate -> isJupiterAssertion(next, candidate, "assertThrows")
                                && candidate.getArguments().size() == 2 && candidate.getArgument(0).isClassExpr()
                                && matchesType(next, candidate.getArgument(0).asClassExpr().getType().asString(), exception)
                                && candidate.getArgument(1).equals(assertion.getArgument(0))).count();
                if (replacements > 0) {
                    result.put(method.getSignature().asString() + ":" + assertion, (int) replacements);
                }
            }
        }
        return result;
    }

    private boolean matchesType(CompilationUnit unit, String actual, String expected) {
        return actual.equals(expected) || unit.getImports().stream().anyMatch(declaration -> !declaration.isStatic()
                && (declaration.getNameAsString().equals(expected) && expected.endsWith("." + actual)
                || declaration.isAsterisk() && (declaration.getNameAsString() + "." + actual).equals(expected)))
                || (unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("") + actual)
                .equals(expected);
    }

    private boolean isJupiterAssertion(CompilationUnit unit, MethodCallExpr call, String name) {
        if (!call.getNameAsString().equals(name)) {
            return false;
        }
        if (call.getScope().isPresent()) {
            return matchesType(unit, call.getScope().get().toString(), "org.junit.jupiter.api.Assertions");
        }
        return unit.getImports().stream().anyMatch(declaration -> declaration.isStatic()
                && (declaration.getNameAsString().equals("org.junit.jupiter.api.Assertions." + name)
                || declaration.isAsterisk() && declaration.getNameAsString().equals("org.junit.jupiter.api.Assertions")));
    }

    private Map<String, Integer> assertionCalls(CompilationUnit unit) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (isEffectiveAssertion(unit, call)) {
                    com.github.javaparser.ast.Node expression = call;
                    while (expression.getParentNode().orElse(null) instanceof MethodCallExpr
                            && ((MethodCallExpr) expression.getParentNode().get()).getScope().orElse(null) == expression) {
                        expression = expression.getParentNode().get();
                    }
                    result.merge(method.getSignature().asString() + ":" + expression, 1, Integer::sum);
                }
            }
        }
        return result;
    }

    private boolean isTest(CompilationUnit unit, MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(annotation -> {
            String name = qualifiedAnnotation(unit, annotation);
            return name.equals("org.junit.Test") || name.equals("org.junit.jupiter.api.Test")
                    || name.equals("org.junit.jupiter.params.ParameterizedTest")
                    || name.equals("org.junit.jupiter.api.RepeatedTest");
        });
    }

    private boolean isDisabled(CompilationUnit unit, AnnotationExpr annotation) {
        String name = qualifiedAnnotation(unit, annotation);
        return name.equals("org.junit.Ignore") || name.equals("org.junit.jupiter.api.Disabled")
                || name.startsWith("org.junit.jupiter.api.condition.");
    }

    private String qualifiedAnnotation(CompilationUnit unit, AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        if (name.contains(".")) {
            return name;
        }
        return unit.getImports().stream().filter(declaration -> !declaration.isStatic())
                .sorted(java.util.Comparator.comparing(declaration -> declaration.isAsterisk()))
                .map(declaration -> declaration.isAsterisk() ? declaration.getNameAsString() + "." + name
                        : declaration.getNameAsString())
                .filter(candidate -> candidate.endsWith("." + name))
                .filter(candidate -> candidate.startsWith("org.junit."))
                .findFirst().orElse(name);
    }

    private int assertionCount(CompilationUnit unit, MethodDeclaration method) {
        int count = 0;
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!isEffectiveAssertion(unit, call)) {
                continue;
            }
            boolean imported = unit.getImports().stream().anyMatch(declaration -> {
                String owner = declaration.getNameAsString();
                boolean known = owner.startsWith("org.junit.") || owner.startsWith("org.assertj.core.api.")
                        || owner.startsWith("org.hamcrest.MatcherAssert") || owner.startsWith("org.mockito.Mockito");
                if (!known) {
                    return false;
                }
                if (!call.getScope().isPresent()) {
                    return declaration.isStatic() && (declaration.isAsterisk() || owner.endsWith("." + name));
                }
                String scope = call.getScope().get().toString();
                return scope.equals(owner) || owner.endsWith("." + scope)
                        || declaration.isAsterisk() && !declaration.isStatic()
                        && (scope.equals("Assertions") || scope.equals("Assert") || scope.equals("Mockito")
                        || scope.equals("MatcherAssert"));
            });
            MethodCallExpr fluentRoot = isAssertJTerminal(name)
                    ? assertJRoot(unit, call.getScope().orElse(null)) : null;
            if (fluentRoot != null) {
                imported = true;
            }
            boolean qualified = call.getScope().map(scope -> scope.toString().matches(
                    "org\\.(junit\\..*|assertj\\.core\\.api\\..*|hamcrest\\.MatcherAssert|mockito\\.Mockito)"))
                    .orElse(false);
            MethodCallExpr arguments = fluentRoot == null ? call : fluentRoot;
            boolean constantOnly = !arguments.getArguments().isEmpty() && arguments.getArguments().stream()
                    .allMatch(argument -> argument.isLiteralExpr() || argument.isUnaryExpr()
                            && argument.asUnaryExpr().getExpression().isLiteralExpr());
            if ((imported || qualified) && !constantOnly && !arguments.getArguments().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private boolean isEffectiveAssertion(CompilationUnit unit, MethodCallExpr call) {
        String name = call.getNameAsString();
        if ("assertThat".equals(name)) {
            return call.getArguments().size() >= 2 && unit.getImports().stream().anyMatch(declaration ->
                    declaration.getNameAsString().startsWith("org.hamcrest.MatcherAssert")
                            || declaration.getNameAsString().startsWith("org.junit.Assert"));
        }
        if (name.equals("verify")) {
            com.github.javaparser.ast.Node parent = call.getParentNode().orElse(null);
            return parent instanceof MethodCallExpr && ((MethodCallExpr) parent).getScope().orElse(null) == call;
        }
        if (name.startsWith("assert") || name.startsWith("verifyNo")) {
            return true;
        }
        if (!isAssertJTerminal(name) || !call.getScope().isPresent()) {
            return false;
        }
        return assertJRoot(unit, call.getScope().get()) != null;
    }

    private boolean isAssertJTerminal(String name) {
        return name.startsWith("is") || name.startsWith("has") || name.startsWith("contains")
                || name.startsWith("doesNotContain") || name.startsWith("doesNotHave")
                || name.equals("startsWith") || name.equals("endsWith")
                || name.equals("matches") || name.equals("satisfies");
    }

    private MethodCallExpr assertJRoot(CompilationUnit unit, com.github.javaparser.ast.expr.Expression expression) {
        if (!(expression instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr call = (MethodCallExpr) expression;
        if (call.getNameAsString().equals("assertThat")) {
            boolean imported = unit.getImports().stream().anyMatch(declaration ->
                    declaration.getNameAsString().startsWith("org.assertj.core.api.Assertions")
                            || declaration.isAsterisk() && declaration.getNameAsString().equals("org.assertj.core.api"));
            boolean qualified = call.getScope().map(scope -> scope.toString()
                    .equals("org.assertj.core.api.Assertions")).orElse(false);
            return imported || qualified ? call : null;
        }
        return call.getScope().isPresent() ? assertJRoot(unit, call.getScope().get()) : null;
    }
}
