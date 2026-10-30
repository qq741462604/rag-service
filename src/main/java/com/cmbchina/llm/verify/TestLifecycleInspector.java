package com.cmbchina.llm.verify;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试生命周期及构造器 Mock 的局部 AST 检查，不替代编译与运行验证。
 *
 * @author summer
 * @date 2026-10-30 12:00:00
 */
final class TestLifecycleInspector {

    private TestLifecycleInspector() {
    }

    static void validate(CompilationUnit unit, List<String> errors) {
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }
            Map<String, VariableDeclarator> instanceFields = new LinkedHashMap<>();
            type.getFields().stream().filter(field -> !field.isStatic()).forEach(field ->
                    field.getVariables().forEach(variable -> instanceFields.put(variable.getNameAsString(), variable)));
            for (MethodDeclaration method : type.getMethods()) {
                validateLifecycle(unit, type, method, errors);
                if (method.isStatic() && method.getBody().isPresent()) {
                    validateStaticMembers(type, method, instanceFields.keySet(), errors);
                }
            }
            validateConstructionReceivers(unit, type, errors);
            validateMockCleanup(unit, type, errors);
        }
    }

    private static void validateLifecycle(CompilationUnit unit, ClassOrInterfaceDeclaration type,
                                          MethodDeclaration method, List<String> errors) {
        boolean each = annotated(unit, method, "org.junit.jupiter.api.BeforeEach")
                || annotated(unit, method, "org.junit.jupiter.api.AfterEach")
                || annotated(unit, method, "org.junit.Before") || annotated(unit, method, "org.junit.After");
        boolean all = annotated(unit, method, "org.junit.jupiter.api.BeforeAll")
                || annotated(unit, method, "org.junit.jupiter.api.AfterAll");
        boolean junit4Class = annotated(unit, method, "org.junit.BeforeClass")
                || annotated(unit, method, "org.junit.AfterClass");
        boolean perMethod = type.getAnnotations().stream().anyMatch(annotation ->
                annotated(unit, annotation, "org.junit.jupiter.api.TestInstance")
                        && (annotation.isSingleMemberAnnotationExpr()
                        && annotation.asSingleMemberAnnotationExpr().getMemberValue().toString().endsWith("PER_METHOD")
                        || annotation.isNormalAnnotationExpr() && annotation.asNormalAnnotationExpr().getPairs().stream()
                        .anyMatch(pair -> pair.getNameAsString().equals("value")
                                && pair.getValue().toString().endsWith("PER_METHOD"))));
        if (each && method.isStatic()) {
            errors.add("逐用例生命周期方法不能为 static: " + type.getNameAsString() + "#" + method.getNameAsString()
                    + "；保留 BeforeEach/AfterEach 或 Before/After 的实例方法及成对清理，不能靠改注解绕过 Mock 冲突。");
        }
        // JUnit5 还可通过继承或平台配置选择 PER_CLASS，仅拦截本类显式 PER_METHOD 的矛盾配置。
        if ((junit4Class || all && perMethod) && !method.isStatic()) {
            errors.add("类级生命周期方法缺少 static 或 JUnit5 明确的 TestInstance(PER_CLASS): "
                    + type.getNameAsString() + "#" + method.getNameAsString()
                    + "；不能只换 BeforeAll/AfterAll 注解，优先修复原逐用例 Mock 的初始化与清理。");
        }
    }

    private static void validateStaticMembers(ClassOrInterfaceDeclaration type, MethodDeclaration method,
                                               Set<String> fields, List<String> errors) {
        Set<String> locals = new LinkedHashSet<>();
        method.getParameters().forEach(parameter -> locals.add(parameter.getNameAsString()));
        method.findAll(VariableDeclarator.class).forEach(variable -> locals.add(variable.getNameAsString()));
        method.findAll(com.github.javaparser.ast.body.Parameter.class)
                .forEach(parameter -> locals.add(parameter.getNameAsString()));
        Set<String> invalid = new LinkedHashSet<>();
        for (NameExpr name : method.findAll(NameExpr.class)) {
            if (belongsToMethod(name, method) && fields.contains(name.getNameAsString())
                    && !locals.contains(name.getNameAsString())) {
                invalid.add(name.getNameAsString());
            }
        }
        method.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                .filter(field -> belongsToMethod(field, method))
                .filter(field -> field.getScope().isThisExpr()
                        || field.getScope().toString().equals(type.getNameAsString()))
                .filter(field -> fields.contains(field.getNameAsString()))
                .forEach(field -> invalid.add(field.getNameAsString()));
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!belongsToMethod(call, method) || call.getScope().isPresent()) {
                continue;
            }
            boolean instance = type.getMethods().stream().anyMatch(candidate -> !candidate.isStatic()
                    && candidate.getNameAsString().equals(call.getNameAsString())
                    && candidate.getParameters().size() == call.getArguments().size());
            boolean statik = type.getMethods().stream().anyMatch(candidate -> candidate.isStatic()
                    && candidate.getNameAsString().equals(call.getNameAsString())
                    && candidate.getParameters().size() == call.getArguments().size());
            if (instance && !statik) {
                invalid.add(call.getNameAsString() + "()");
            }
        }
        if (!invalid.isEmpty()) {
            errors.add("静态方法访问本类实例成员: " + type.getNameAsString() + "#" + method.getNameAsString()
                    + " -> " + invalid + "；改 BeforeAll/AfterAll 并加 static 不能保留实例字段访问。"
                    + "优先保留逐用例生命周期，修复 Mock 的创建、异常清理与状态恢复，禁止把全部测试状态改成 static。");
        }
    }

    private static void validateMockCleanup(CompilationUnit unit, ClassOrInterfaceDeclaration type,
                                             List<String> errors) {
        for (VariableDeclarator field : type.getFields().stream().flatMap(value -> value.getVariables().stream())
                .collect(java.util.stream.Collectors.toList())) {
            if (!field.getType().isClassOrInterfaceType()) {
                continue;
            }
            String kind = field.getType().asClassOrInterfaceType().getNameAsString();
            if (!kind.equals("MockedStatic") && !kind.equals("MockedConstruction")) {
                continue;
            }
            boolean createdPerTest = type.getMethods().stream().filter(method ->
                    annotated(unit, method, "org.junit.jupiter.api.BeforeEach")
                            || annotated(unit, method, "org.junit.Before"))
                    .flatMap(method -> method.findAll(AssignExpr.class).stream())
                    .anyMatch(assignment -> fieldName(assignment.getTarget()).equals(field.getNameAsString())
                            && assignment.getValue().isMethodCallExpr()
                            && assignment.getValue().asMethodCallExpr().getNameAsString().startsWith("mock"));
            boolean closedPerTest = type.getMethods().stream().filter(method ->
                    annotated(unit, method, "org.junit.jupiter.api.AfterEach")
                            || annotated(unit, method, "org.junit.After")
                            || annotated(unit, method, "org.junit.jupiter.api.BeforeEach")
                            || annotated(unit, method, "org.junit.Before"))
                    .flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                    .anyMatch(call -> (call.getNameAsString().equals("close") || call.getNameAsString().equals("closeOnDemand"))
                            && call.getScope().map(scope -> fieldName(scope).equals(field.getNameAsString())).orElse(false));
            if (createdPerTest && !closedPerTest && type.getExtendedTypes().isEmpty()) {
                // 辅助清理方法无法在此可靠解析，不将它误判为泄漏。
                boolean helperCleanup = type.getMethods().stream().filter(method ->
                        annotated(unit, method, "org.junit.jupiter.api.AfterEach")
                                || annotated(unit, method, "org.junit.After"))
                        .flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                        .anyMatch(call -> call.getArguments().stream()
                                .anyMatch(argument -> fieldName(argument).equals(field.getNameAsString()))
                                || (!call.getScope().isPresent() || call.getScope().get().isThisExpr())
                                && type.getMethodsByName(call.getNameAsString()).stream().anyMatch(helper ->
                                helper.findAll(NameExpr.class).stream().anyMatch(name ->
                                        name.getNameAsString().equals(field.getNameAsString()))
                                        || !helper.findAll(MethodCallExpr.class).isEmpty()));
                if (!helperCleanup) {
                    errors.add("逐用例创建的 " + kind + " 句柄缺少逐用例清理: " + field.getNameAsString()
                            + "；BeforeEach/Before 与 AfterEach/After 必须配对，不能用 AfterAll 关闭每轮新建的 Mock。");
                }
            }
        }
    }

    /** 仅识别已知 HTTP 创建链中，构造回调捕获字段在业务构造之前就参与 stub 的直接模式。 */
    private static void validateConstructionReceivers(CompilationUnit unit, ClassOrInterfaceDeclaration type,
                                                       List<String> errors) {
        Set<String> fields = new LinkedHashSet<>();
        type.getFields().forEach(field -> field.getVariables().stream().filter(variable -> !variable.getInitializer().isPresent())
                .forEach(variable -> fields.add(variable.getNameAsString())));
        for (MethodCallExpr construction : type.findAll(MethodCallExpr.class)) {
            if (!construction.getNameAsString().equals("mockConstruction") || construction.getArguments().size() != 2
                    || !construction.getArgument(0).isClassExpr() || !construction.getArgument(1).isLambdaExpr()) {
                continue;
            }
            LambdaExpr callback = construction.getArgument(1).asLambdaExpr();
            if (callback.getParameters().size() != 2) {
                continue;
            }
            String mocked = callback.getParameter(0).getNameAsString();
            String className = construction.getArgument(0).asClassExpr().getType().asString();
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            if (!simpleName.equals("AhiHttpInvokerHandler") && !simpleName.equals("HttpClientUtil")) {
                continue;
            }
            Set<String> captured = new LinkedHashSet<>();
            callback.findAll(AssignExpr.class).stream().filter(assignment -> assignment.getValue().toString().equals(mocked))
                    .map(assignment -> fieldName(assignment.getTarget())).filter(fields::contains).forEach(captured::add);
            if (captured.isEmpty() || type.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                    .anyMatch(creation -> creation.getType().asString().equals(className)
                            || className.endsWith("." + creation.getType().getNameAsString()))) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (!annotated(unit, method, "org.junit.jupiter.api.Test") && !annotated(unit, method, "org.junit.Test")) {
                    continue;
                }
                for (MethodCallExpr stub : method.findAll(MethodCallExpr.class)) {
                    if (!stub.getNameAsString().equals("when") || stub.getArguments().size() != 1
                            || stub.findAncestor(LambdaExpr.class).isPresent()) {
                        continue;
                    }
                    Expression argument = stub.getArgument(0);
                    String receiver = argument.isMethodCallExpr() ? argument.asMethodCallExpr().getScope()
                            .map(TestLifecycleInspector::fieldName).orElse("") : fieldName(argument);
                    if (!captured.contains(receiver)) {
                        continue;
                    }
                    if (method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(receiver))
                            || method.findAll(VariableDeclarator.class).stream()
                            .anyMatch(variable -> variable.getNameAsString().equals(receiver))) {
                        continue;
                    }
                    boolean assignedElsewhere = type.findAll(AssignExpr.class).stream()
                            .anyMatch(assignment -> fieldName(assignment.getTarget()).equals(receiver)
                                    && !assignment.findAncestor(LambdaExpr.class).map(callback::equals).orElse(false));
                    boolean earlierBusinessCall = method.findAll(MethodCallExpr.class).stream()
                            .anyMatch(call -> (call.getNameAsString().equals("handle") || call.getNameAsString().equals("doPost"))
                                    && !insideStub(call)
                                    && call.getBegin().isPresent() && stub.getBegin().isPresent()
                                    && call.getBegin().get().isBefore(stub.getBegin().get()));
                    // 辅助夹具可能间接构造被 Mock 类型，不能仅凭本文件缺少直接 new 就判为 null。
                    boolean customFixture = type.getMethods().stream().filter(candidate -> candidate != method)
                            .filter(candidate -> !annotated(unit, candidate, "org.junit.jupiter.api.Test")
                                    && !annotated(unit, candidate, "org.junit.Test"))
                            .filter(candidate -> candidate.findAll(MethodCallExpr.class).stream().noneMatch(call ->
                                    call.getNameAsString().equals("mockConstruction")))
                            .anyMatch(candidate -> candidate.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                                    .stream().anyMatch(creation -> creation.getType().asString().equals(className))
                                    || candidate.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                                    (call.getNameAsString().equals("handle") || call.getNameAsString().equals("doPost"))
                                            && !insideStub(call)));
                    if (!assignedElsewhere && !earlierBusinessCall && !customFixture) {
                        errors.add("构造器 Mock 捕获字段在构造前被用来 stub: " + method.getNameAsString() + " -> " + receiver
                                + "。该字段只在 mockConstruction 回调执行后赋值，当前可能为 null；"
                                + "把桩放在初始化回调的 mock 参数上，业务调用后再取 constructed() 验证，不能人为构造对象迁就用例。");
                    }
                }
            }
        }
    }

    private static boolean belongsToMethod(com.github.javaparser.ast.Node node, MethodDeclaration method) {
        return node.findAncestor(MethodDeclaration.class).map(method::equals).orElse(false);
    }

    private static String fieldName(Expression expression) {
        return expression.isNameExpr() ? expression.asNameExpr().getNameAsString()
                : expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()
                ? expression.asFieldAccessExpr().getNameAsString() : "";
    }

    private static boolean annotated(CompilationUnit unit, MethodDeclaration method, String qualified) {
        return method.getAnnotations().stream().anyMatch(annotation -> annotated(unit, annotation, qualified));
    }

    private static boolean annotated(CompilationUnit unit, AnnotationExpr annotation, String qualified) {
        return annotation.getNameAsString().equals(qualified)
                || annotation.getNameAsString().equals(qualified.substring(qualified.lastIndexOf('.') + 1))
                && unit.getImports().stream().anyMatch(declaration -> !declaration.isStatic()
                && (declaration.getNameAsString().equals(qualified) || declaration.isAsterisk()
                && declaration.getNameAsString().equals(qualified.substring(0, qualified.lastIndexOf('.')))));
    }

    private static boolean insideStub(MethodCallExpr call) {
        for (com.github.javaparser.ast.Node node = call; node.getParentNode().isPresent(); node = node.getParentNode().get()) {
            if (node instanceof MethodCallExpr) {
                String name = ((MethodCallExpr) node).getNameAsString();
                if (name.equals("when") || name.startsWith("then") || name.equals("verify")) {
                    return true;
                }
            }
        }
        return false;
    }
}
