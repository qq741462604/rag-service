package com.cmbchina.llm.verify;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
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

    public void preserveAssertions(String before, String after) {
        CompilationUnit previous = parse(before);
        CompilationUnit next = parse(after);
        Map<String, Integer> calls = assertionCalls(next);
        for (Map.Entry<String, Integer> call : assertionCalls(previous).entrySet()) {
            if (calls.getOrDefault(call.getKey(), 0) < call.getValue()) {
                throw new IllegalArgumentException("禁止删除或放宽已有断言/交互验证: " + call.getKey());
            }
        }
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
