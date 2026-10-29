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
