package com.cmbchina.llm.tool;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.util.StreamUtil;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行 Maven 测试的工具。
 *
 * <p>增强能力：</p >
 * <ul>
 *     <li>支持按指定测试类执行（mvn test -Dtest=XxxTest），缩短闭环时间</li>
 *     <li>设置超时保护，避免 mvn test 卡死</li>
 *     <li>捕获退出码，区分编译失败 / 测试失败 / 成功</li>
 *     <li>解析测试失败信息（编译错误 / 断言失败 / 异常堆栈摘要）</li>
 * </ul>
 *
 * @author IT006901
 * @date 2026-10-13 10:20:00
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunTestTool extends BaseTool {

    /** 测试执行超时时间（最长等待 5 分钟，避免 mvn test 卡死拖垮整个 Agent 流程） */
    private static final long TEST_TIMEOUT_SECONDS = 300;

    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "run_test";
    }

    @Override
    public String description() {
        return "run project test with timeout, support -Dtest specific test class";
    }

    @Override
    public Object execute(Object input, AgentContext context) throws Exception {
        ToolInput toolInput = (ToolInput) input;
        String testClass = toolInput != null ? toolInput.getKeyword() : null;
        long timeoutSeconds = toolInput != null && toolInput.getLimit() != null
                ? toolInput.getLimit()
                : TEST_TIMEOUT_SECONDS;

        ProcessBuilder pb = new ProcessBuilder(buildCommand(testClass))
                .directory(workspaceService.getRoot())
                .redirectErrorStream(true);

        log.info("run_test start command={} timeout={}s", String.join(" ", buildCommand(testClass)), timeoutSeconds);

        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            log.error("run_test failed to start process: {}", e.getMessage());
            List<String> errorMessages = new ArrayList<>();
            errorMessages.add("mvn 命令启动失败: " + e.getMessage());
            return TestRunResult.builder()
                    .success(false)
                    .exitCode(-1)
                    .output("Failed to start mvn test process: " + e.getMessage())
                    .errorMessages(errorMessages)
                    .build();
        }

        // 超时等待
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            log.warn("run_test timeout after {}s, destroy process", timeoutSeconds);
            p.destroyForcibly();
            List<String> errorMessages = new ArrayList<>();
            errorMessages.add("测试执行超时（" + timeoutSeconds + "秒），已强制终止");

            return TestRunResult.builder()
                    .success(false)
                    .exitCode(-1)
                    .output("mvn test timed out after " + timeoutSeconds + "s")
                    .errorMessages(errorMessages)
                    .build();
        }

        int exitCode = p.exitValue();
        String output = StreamUtil.read(p.getInputStream());
        log.info("run_test finished exitCode={} outputLength={}", exitCode, output.length());

        // 解析失败信息
        List<String> errorMessages = parseErrors(output, exitCode);

        boolean success = exitCode == 0;
        return TestRunResult.builder()
                .success(success)
                .exitCode(exitCode)
                .output(output)
                .errorMessages(errorMessages)
                .build();
    }

    /**
     * 构建 mvn 命令。
     *
     * @param testClass 可选：指定测试类名（如 XxxTest），为 null 时执行全量测试
     * @return 命令参数列表
     */
    private List<String> buildCommand(String testClass) {
        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        cmd.add("test");
        if (testClass != null && !testClass.trim().isEmpty()) {
            cmd.add("-Dtest=" + testClass.trim());
            // 指定测试类时跳过其他测试，加速反馈
            cmd.add("-DfailIfNoTests=false");
        }
        return cmd;
    }
    /**
     * 从 mvn 输出中解析关键失败信息。
     *
     * <p>提取以下几类信息：</p >
     * <ul>
     *     <li>编译错误（ERROR ... .java:[行号,列号] 无法解析/找不到符号）</li>
     *     <li>测试失败（Tests run: X, Failures: Y, Errors: Z）</li>
     *     <li>断言失败（expected: ... but was: ...）</li>
     *     <li>异常堆栈摘要（首个 Caused by 段落）</li>
     * </ul>
     *
     * @param output   mvn 完整输出
     * @param exitCode mvn 退出码
     * @return 失败信息列表（按重要性排序）
     */
    private List<String> parseErrors(String output, int exitCode) {
        List<String> errors = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return errors;
        }
        String[] lines = output.split("\n");

        // 1. 汇总测试统计行
        StringBuilder testStats = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Tests run:")
                    && (trimmed.contains("Failures:") || trimmed.contains("Errors:"))) {
                testStats.append(line).append("\n");
            }
        }
        if (testStats.length() > 0) {
            errors.add("测试统计:\n" + testStats.toString().trim());
        }

        // 2. 编译错误（ERROR / [ERROR] 行包含 .java 与行号）
        StringBuilder compileErrors = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[ERROR]")
                    && trimmed.contains(".java:")
                    && (trimmed.contains("找不到")
                    || trimmed.contains("无法")
                    || trimmed.contains("cannot")
                    || trimmed.contains("不兼容")
                    || trimmed.contains("error:"))) {
                compileErrors.append(trimmed.replace("[ERROR]", "")).append("\n");
            }
        }
        if (compileErrors.length() > 0) {
            errors.add("编译错误:\n" + compileErrors.toString().trim());
        }

        // 3. 断言失败（expected / but was / AssertionError）
        StringBuilder assertionErrors = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("AssertionError")
                    || trimmed.contains("expected:")
                    || trimmed.contains("but was:")
                    || trimmed.contains("expecting")) {
                assertionErrors.append(trimmed).append("\n");
            }
        }
        if (assertionErrors.length() > 0) {
            errors.add("断言失败:\n" + assertionErrors.toString().trim());
        }

        // 4. 首个异常堆栈（Caused by 段落，最多取 10 行）
        StringBuilder stackTrace = new StringBuilder();
        boolean inCause = false;
        int causeCount = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by:")) {
                inCause = true;
                causeCount++;
                if (causeCount > 3) {
                    break;
                }
                stackTrace.append(trimmed).append("\n");
                continue;
            }
            if (inCause) {
                if (trimmed.isEmpty() && stackTrace.length() > 0) {
                    break;
                }
                stackTrace.append("\t").append(trimmed).append("\n");
            }
            if (stackTrace.length() > 500) {
                stackTrace.append("\t... (堆栈过长，已截断)");
                break;
            }
        }
        if (stackTrace.length() > 0) {
            errors.add("异常堆栈:\n" + stackTrace.toString().trim());
        }

        // 5. BUILD FAILURE 摘要
        for (String line : lines) {
            if (line.contains("BUILD FAILURE")) {
                errors.add("构建结果: BUILD FAILURE");
                break;
            }
        }

        // 6. 若成功但仍有 WARNING，记入无关紧要列表（不加入 errors）
        if (errors.isEmpty() && exitCode != 0) {
            errors.add("测试执行失败，退出码=" + exitCode);
        }
        return errors;
    }

    /**
     * 测试执行结果。
     */
    @Data
    @Builder
    public static class TestRunResult {
        /** 测试是否通过 */
        private boolean success;
        /** 进程退出码（0=成功，非0=失败，-1=启动失败/超时） */
        private int exitCode;
        /** mvn 完整输出 */
        private String output;
        /** 解析出的失败信息列表 */
        private List<String> errorMessages;
    }
}