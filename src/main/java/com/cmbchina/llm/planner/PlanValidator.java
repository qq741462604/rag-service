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
 * <p>注意：write_file / create_file 已放开"不得覆盖已存在文件"的限制。
 * 分轮读取模板生成代码时，下一轮常会遇到已存在文件，这是正常场景，
 * 不应校验失败阻断流程。</p >
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
        // write_file / create_file 已放开已存在文件限制，不再校验存在性
        if ("patch_file".equals(tool)) {
            validatePatchTool(input);
        }
    }

    /**
     * 校验 CODING 阶段写入路径是否符合当前子行程约束。
     *
     * <p>S1_BUSINESS 阶段只允许写入 src/main 目录，禁止写 src/test（防止 LLM 在 S1 阶段
     * 顺便生成测试类导致 S2 被跳过）；S2_TEST 阶段的约束有所放宽：
     * 仅禁止 write_file 整文件覆盖 src/main（防止 S2 阶段重写业务代码），
     * 但允许 patch_file 局部修改 src/main（测试失败后往往需要小幅修复业务代码），
     * 使得 S2 阶段既能写测试类、又能针对测试失败定位修复 src/main 的编译/逻辑错误。</p >
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
        // S2_TEST：write_file 整文件覆盖 src/main 禁止，patch_file 局部修改 src/main 允许
        // （测试失败后 LLM 需要修复业务代码的编译/逻辑错误，patch_file 是精确局部修改）。
        if (track == CodingTrack.S2_TEST
                && normalized.contains("/main/")
                && "write_file".equals(tool)) {
            throw new RuntimeException(
                    tool + " blocked: S2 phase cannot write_file src/main path=" + path
                            + ", 如需修改业务代码请使用 patch_file 局部修改"
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

    private boolean isEmptyFile(String path) {
        try {
            File file = workspaceService.resolveExistingFile(path);
            return file.exists() && file.isFile() && file.length() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
