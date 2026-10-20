package com.cmbchina.llm.verify;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.tool.RunTestTool;
import com.cmbchina.llm.tool.ToolInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyEngine {

    private final CodeValidator validator;
    private final VerifyFileLoader loader;
    private final RunTestTool runTestTool;

    @Value("${codex.agent.test-generation-enabled:false}")
    private boolean testGenerationEnabled;

    /**
     * 执行验证。
     *
     * <p>两层验证：
     * 1. 静态代码检查（CodeValidator，JavaParser 语法/依赖/规范校验）
     * 2. 静态检查通过后，执行测试类（RunTestTool，运行 mvn test）</p >
     *
     * @param context Agent 上下文
     * @return 验证结果（静态检查 + 测试执行结果）
     */
    public VerifyResult verify(AgentContext context) {
        // 第一层：静态代码检查
        loader.loadChangedFiles(context);
        List<String> staticErrors = validator.validate(context);
        if (!staticErrors.isEmpty()) {
            log.warn("static verify failed errors={}", staticErrors);
            return VerifyResult.failed(staticErrors);
        }
        if (!testGenerationEnabled) {
            log.info("static verify passed; S2 paused, skip test generation and mvn test");
            return VerifyResult.success();
        }
        log.info("static verify passed, start running tests");

        // 第二层：执行测试
        try {
            // 从 observations 中提取测试类名，避免执行全量测试
            String testClassName = extractTestClassNames(context);
            if (testClassName != null && !testClassName.trim().isEmpty()) {
                log.info("run_test using extracted test class: {}", testClassName);
            } else {
                log.warn("run_test no test class extracted, will execute all tests (this may be slow)");
            }
            RunTestTool.TestRunResult testResult =
                    (RunTestTool.TestRunResult) runTestTool.execute(
                            ToolInput.builder()
                                    .keyword(testClassName)
                                    .build(),
                            context);
            if (testResult.isSuccess()) {
                log.info("test passed exitCode={}", testResult.getExitCode());
                return VerifyResult.testSuccess(testResult.getOutput());
            } else {
                log.warn("test failed exitCode={} errors={}",
                        testResult.getExitCode(), testResult.getErrorMessages());
                return VerifyResult.testFailed(
                        testResult.getErrorMessages(),
                        testResult.getExitCode(),
                        testResult.getOutput()
                );
            }
        } catch (Exception e) {
            log.error("run_test execution error", e);
            List<String> errors = new ArrayList<>();
            errors.add("测试执行异常: " + e.getMessage());

            return VerifyResult.testFailed(
                    errors,
                    -1,
                    null
            );
        }
    }

    /**
     * 从 observations 中提取测试类名。
     *
     * <p>从 create_file / write_file 的 observations 中提取 src/test 目录下的测试文件路径，
     * 然后从文件路径中提取测试类名（去掉 .java 后缀和 Test 后缀）。</p >
     *
     * <p>例如：src/test/java/com/example/XxxTest.java -> com.example.XxxTest</p >
     *
     * @param context Agent 上下文
     * @return 测试类名（带包名），如果无法提取则返回 null
     */
    private String extractTestClassNames(AgentContext context) {
        if (context == null || context.getObservations() == null || context.getObservations().isEmpty()) {
            return null;
        }

        Set<String> testClassNames = new HashSet<>();

        for (Observation observation : context.getObservations()) {
            // 只处理 create_file 和 write_file 的成功观察
            if (!"create_file".equals(observation.getTool())
                    && !"write_file".equals(observation.getTool())) {
                continue;
            }
            if (!observation.isSuccess() || observation.getResult() == null) {
                continue;
            }

            // 从 observation.input 中提取文件路径
            String filePath = extractFilePathFromObservation(observation);
            if (filePath == null || filePath.isEmpty()) {
                continue;
            }

            // 只处理 src/test 目录下的测试文件
            if (!filePath.contains("src/test/") && !filePath.startsWith("src/test/")) {
                continue;
            }

            // 只处理 .java 文件
            if (!filePath.endsWith(".java")) {
                continue;
            }

            // 从文件路径中提取类名
            String className = extractClassNameFromPath(filePath);
            if (className != null && !className.trim().isEmpty()) {
                testClassNames.add(className);
            }
        }

        if (testClassNames.isEmpty()) {
            return null;
        }

        // 返回所有测试类名，用逗号分隔（mvn test -Dtest=XxxTest,YyyTest）
        return String.join(",", testClassNames);
    }
    /**
     * 从 observation 中提取文件路径。
     */
    private String extractFilePathFromObservation(Observation observation) {
        if (observation.getInput() == null) {
            return null;
        }

        try {
            if (observation.getInput() instanceof ToolInput) {
                return ((ToolInput) observation.getInput()).getPath();
            } else if (observation.getInput() instanceof Map) {
                Object pathNode = ((Map<?, ?>) observation.getInput()).get("path");
                if (pathNode != null && pathNode instanceof String) {
                    return (String) pathNode;
                }
            }
        } catch (Exception e) {
            log.warn("extractFilePathFromObservation error", e);
        }

        return null;
    }

    /**
     * 从文件路径中提取类名。
     *
     * <p>例如：src/test/java/com/example/XxxTest.java -> com.example.XxxTest</p >
     *
     * @param filePath 文件路径
     * @return 类名（带包名），如果无法提取则返回 null
     */
    private String extractClassNameFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        try {
            // 提取文件名（去掉路径）
            String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
            if (!fileName.endsWith(".java")) {
                return null;
            }

            // 去掉 .java 后缀
            String className = fileName.substring(0, fileName.length() - 5);

            // 从路径中提取包名
            String basePath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
            int srcTestIndex = basePath.lastIndexOf("src/test/");
            if (srcTestIndex >= 0) {
                String packagePath = basePath.substring(srcTestIndex + 9); // 去掉 "src/test/"
                // 去掉最后的文件名部分
                packagePath = packagePath.substring(0, packagePath.lastIndexOf('/') + 1);
                // 将 / 转换为 .
                String packageName = packagePath.replace('/', '.').replace('\\', '.');
                // 去掉末尾的点
                if (packageName.endsWith(".")) {
                    packageName = packageName.substring(0, packageName.length() - 1);
                }

                if (packageName != null && !packageName.isEmpty()) {
                    return packageName + "." + className;
                } else {
                    return className;
                }
            }

            return className;
        } catch (Exception e) {
            log.warn("extractClassNameFromPath error filePath={}", filePath, e);
            return null;
        }
    }
}
