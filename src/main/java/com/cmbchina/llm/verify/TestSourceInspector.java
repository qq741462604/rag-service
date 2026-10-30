package com.cmbchina.llm.verify;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            throw new IllegalArgumentException("Java 语法解析失败: " + result.getProblems());
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
        List<String> errors = new ArrayList<>();
        CompilationUnit unit = parse(source);
        validateHttpClientMocks(unit, errors);
        validateHttpClientReferences(unit, errors);
        validateHttpMockLifetime(unit, errors);
        validateHttpPoolConfiguration(unit, errors);
        TestLifecycleInspector.validate(unit, errors);
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
            }
        }
        if (tests == 0) {
            errors.add("缺少可执行的 JUnit4/5 测试方法，不支持用空壳或其他框架绕过");
        }
        return errors;
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
                "org.apache.http.HttpVersion", "org.apache.http.impl.conn.PoolingHttpClientConnectionManager"};
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

    /** 检查共享 setup 的连接池夹具；方法内刻意验证非法值的测试不在此处拦截。 */
    private void validateHttpPoolConfiguration(CompilationUnit unit, List<String> errors) {
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
            validateUnconfiguredPoolMock(unit, method, configurations, errors);
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
                if (!(getter.getNameAsString().equals("getHttpMaxtotalConnections")
                        || getter.getNameAsString().equals("getHttpMaxperrouteConnections"))
                        || !getter.getScope().isPresent()
                        || !configurations.contains(getter.getScope().get().toString())) {
                    continue;
                }
                com.github.javaparser.ast.expr.Expression value = call.getArgument(0);
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
                                              java.util.Set<String> configurations, List<String> errors) {
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
        boolean helper = setup.findAll(MethodCallExpr.class).stream().anyMatch(call ->
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
            boolean indirectSetup = setup.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    !call.getNameAsString().equals("thenReturn")
                            && call.getArguments().stream().anyMatch(argument -> argument.toString().equals(configuration)));
            if (!plainMock || !bean || indirectSetup) {
                continue;
            }
            for (String getter : java.util.Arrays.asList("getHttpMaxtotalConnections", "getHttpMaxperrouteConnections")) {
                boolean configured = unit.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        call.getNameAsString().equals(getter) && call.getScope()
                                .map(scope -> scope.toString().equals(configuration)).orElse(false));
                if (!configured) {
                    errors.add("普通 ReadConfig Mock " + configuration + " 已作为 Spring Bean 返回，但未配置 "
                            + getter + "，其默认返回0；mockConstruction(AhiHttpInvokerHandler.class) 仍会触发"
                            + "静态 HttpClientUtil 的真实连接池初始化。首次使用前为两个连接池容量设置正数，"
                            + "或改用完整真实配置夹具，不能删除业务静态初始化。");
                }
            }
        }
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

    public Map<String, Integer> assertions(String source) {
        CompilationUnit unit = parse(source);
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
        CompilationUnit previous = parse(before);
        CompilationUnit next = parse(after);
        normalizePrivateFieldAssertions(previous, next, businessSource, testClass, verifyErrors);
        Map<String, Integer> calls = assertionCalls(next);
        Map<String, Integer> corrections = exceptionAssertionCorrections(
                previous, next, businessSource, testClass, verifyErrors);
        for (Map.Entry<String, Integer> call : assertionCalls(previous).entrySet()) {
            if (calls.getOrDefault(call.getKey(), 0) + corrections.getOrDefault(call.getKey(), 0)
                    < call.getValue()) {
                throw new IllegalArgumentException("禁止删除或放宽已有断言/交互验证: " + call.getKey());
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
