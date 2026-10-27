package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.CodingTrack;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 代码计划校验器。
 *
 * <p>校验计划中每个文件操作步骤的合法性，包括路径非空、patch 目标存在性
 * 以及 CODING 双子行程路径约束（S1 只能写 src/main，S2 只能写 src/test）。</p >
 *
 * <p>已有文件必须先读后 patch，禁止通过新建工具覆盖原有代码。</p >
 *
 * @author IT006901
 * @date 2026-10-10 15:40:00
 */
@Component
@RequiredArgsConstructor
public class PlanValidator {

    private final WorkspaceService workspaceService;

    /**
     * 校验计划中每个步骤的合法性。
     *
     * @param plan    待校验的计划
     * @param context Agent 上下文（用于区分 S1/S2 子行程，施加路径写入约束）
     */
    public void validate(CodePlan plan, AgentContext context) {
        if (plan == null || plan.getSteps() == null) {
            return;
        }
        for (PlanStep step : plan.getSteps()) {
            validateFileStep(step, context);
        }
    }

    private void validateFileStep(PlanStep step, AgentContext context) {
        if (!(step.getInput() instanceof ToolInput)) {
            return;
        }
        ToolInput input = (ToolInput) step.getInput();
        String path = input.getPath();
        String tool = step.getTool();
        if (isFileMutationTool(tool) && (path == null || path.trim().isEmpty())) {
            throw new RuntimeException(tool + " path is empty");
        }
        // CODING 双子行程路径约束：S1 只能写 src/main，S2 只能写 src/test
        if (context != null
                && context.getPhase() == AgentPhase.CODING
                && isFileMutationTool(tool)) {
            validateCodingTrackPath(path, tool, context);
        }
        if (isCreateTool(tool) && isExistingFileExact(path)) {
            throw new IllegalArgumentException("existing file requires read_file then patch_file path=" + path);
        }
        if ("patch_file".equals(tool)) {
            validatePatchTool(input);
        }
    }

    /**
     * 校验 CODING 阶段写入路径是否符合当前子行程约束。
     *
     * <p>S1_BUSINESS 阶段只允许写入 src/main 目录，禁止写 src/test（防止 LLM 在 S1 阶段
     * 顺便生成测试类导致 S2 被跳过）；S2_TEST 生成阶段不能修改业务代码。
     * 测试失败后由 REPAIR 在本任务清单范围内局部修复。</p >
     *
     * @param path    文件路径
     * @param tool    工具名称
     * @param context Agent 上下文
     */
    private void validateCodingTrackPath(String path, String tool, AgentContext context) {
        String normalized = path.replace('\\', '/');
        CodingTrack track = context.getCodingTrack();
        if (track == CodingTrack.S1_BUSINESS && normalized.contains("/test/")) {
            throw new RuntimeException(

                    tool + " blocked: S1 phase only allows src/main, cannot write src/test path=" + path
            );
        }
        // 测试生成与业务修复分阶段，不能在补测试时顺便改写业务。
        if (track == CodingTrack.S2_TEST && normalized.contains("/main/")) {
            throw new RuntimeException(
                    tool + " blocked: S2 phase cannot write_file src/main path=" + path
                            + ", 生成阶段不能修改业务，测试失败后在 REPAIR 局部修复"
            );
        }
    }

    private void validatePatchTool(ToolInput input) {
        String path = input.getPath();
        if (!isExistingFile(path)) {
            throw new RuntimeException("patch_file target file not found path=" + path);
        }
        if (input.getNewText() == null) {
            throw new RuntimeException("patch_file newText is null path=" + path);
        }
        if (isEmptyFile(path)) {
            return;
        }
        if (input.getOldText() == null || input.getOldText().trim().isEmpty()) {
            throw new RuntimeException("patch_file oldText is empty path=" + path);
        }
    }

    private boolean isCreateTool(String tool) {
        return "write_file".equals(tool) || "create_file".equals(tool);
    }

    private boolean isFileMutationTool(String tool) {
        return isCreateTool(tool) || "patch_file".equals(tool);
    }

    private boolean isExistingFile(String path) {
        try {
            // 使用"按文件名唯一匹配"的纠正解析，避免模型漏写包路径段导致误判文件不存在
            File file = workspaceService.resolveExistingFile(path);
            return file.exists() && file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isExistingFileExact(String path) {
        try {
            File file = workspaceService.resolve(path);
            return file.exists() && file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isEmptyFile(String path) {
        try {
            File file = workspaceService.resolveExistingFile(path);
            return file.exists() && file.isFile() && file.length() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
