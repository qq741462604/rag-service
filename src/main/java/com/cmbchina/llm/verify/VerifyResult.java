package com.cmbchina.llm.verify;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证结果。
 *
 * <p>包含静态代码检查结果和测试执行结果两部分：
 * 静态检查失败时不再执行测试；静态检查通过后执行测试，
 * 测试结果写入 testResult 相关字段。</p >
 *
 * @author IT006901
 * @date 2026-10-13 10:25:00
 */
@Data
@Builder
public class VerifyResult {

    /** 整体验证是否通过（静态检查 + 测试均通过才为 true） */
    private boolean success;

    /** 环境/读取异常不交给模型盲目修复。 */
    private boolean blocked;

    private int testsRun;
    private int skipped;

    @Builder.Default
    private List<String> reports = new ArrayList<>();

    /** 静态检查 / 验证错误列表 */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    /** 失败文件列表 */
    @Builder.Default
    private List<String> failedFiles = new ArrayList<>();

    /** 是否执行了测试 */
    private boolean testExecuted;

    /** 测试是否通过 */
    private boolean testPassed;

    /** 测试进程退出码（0=成功，非0=失败，-1=启动失败/超时） */
    private int testExitCode;

    /** 测试工具解析出的失败信息列表（编译错误/断言失败/异常堆栈等） */
    @Builder.Default
    private List<String> testErrorMessages = new ArrayList<>();

    /** mvn 测试完整输出（截断后保存，避免上下文过大） */
    private String testOutput;

    public static VerifyResult success() {
        return VerifyResult.builder()
                .success(true)
                .build();
    }

    public static VerifyResult failed(List<String> errors) {
        return VerifyResult.builder()
                .success(false)
                .errors(errors)
                .build();
    }

    /**
     * 构建测试成功结果。
     */
    public static VerifyResult testSuccess(String output) {
        return VerifyResult.builder()
                .success(true)
                .testExecuted(true)
                .testPassed(true)
                .testExitCode(0)
                .testOutput(truncate(output))
                .build();
    }

    /**
     * 构建测试失败结果。
     *
     * @param testErrorMessages 测试失败信息（编译错误/断言失败/异常堆栈）
     * @param exitCode          进程退出码
     * @param output            mvn 完整输出
     */
    public static VerifyResult testFailed(
            List<String> testErrorMessages,
            int exitCode,
            String output
    ) {
        List<String> allErrors = new ArrayList<>(testErrorMessages == null ? new ArrayList<>() : testErrorMessages);
        return VerifyResult.builder()
                .success(false)
                .errors(allErrors)
                .testExecuted(true)
                .testPassed(false)
                .testExitCode(exitCode)
                .testErrorMessages(allErrors)
                .testOutput(truncate(output))
                .build();
    }

    private static String truncate(String output) {
        if (output == null) {
            return null;
        }
        return output.length() <= 8192 ? output : output.substring(output.length() - 8192);
    }
}
