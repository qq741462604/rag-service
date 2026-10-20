package com.cmbchina.llm.runtime;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.agent.policy.AgentPolicy;
import com.cmbchina.llm.planner.CodePlan;
import com.cmbchina.llm.planner.PlanStep;
import com.cmbchina.llm.planner.SkillTemplateCoordinator;
import com.cmbchina.llm.tool.AgentTool;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.trace.TraceStep;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final AgentPolicy agentPolicy;
    private final WorkspaceService workspaceService;
    private final SkillTemplateCoordinator skillTemplateCoordinator;

    public List<ToolCallResult> execute(CodePlan plan, AgentContext context) {
        int beforeSize = context.getObservations().size();
        List<ToolCallResult> results = new ArrayList<>();
        for (PlanStep step : plan.getSteps()) {
            if (shouldSkipDuplicateCreate(step, context)) {
                addFailure(results, context, step, "file already created in current agent run");
                continue;
            }
            if (!agentPolicy.allow(context.getPhase(), step.getTool())) {
                log.warn("tool blocked phase={} tool={}", context.getPhase(), step.getTool());
                addFailure(results, context, step, "tool not allowed in phase " + context.getPhase());
                continue;
            }
            AgentTool tool = toolRegistry.getTool(step.getTool());
            if (tool == null) {
                addFailure(results, context, step, "tool not found");
                continue;
            }
            executeTool(step, context, results, tool);
        }
        List<Observation> currentObservations = context.getObservations().subList(
                beforeSize,
                context.getObservations().size()
        );
        context.setLastObservations(new ArrayList<>(currentObservations));
        return results;
    }

    private void addFailure(
            List<ToolCallResult> results,
            AgentContext context,
            PlanStep step,
            String error
    ) {
        results.add(ToolCallResult.failed(step.getTool(), error));
        context.getObservations().add(Observation.builder()
                .tool(step.getTool())
                .success(false)
                .input(step.getInput())
                .error(error)
                .build());
    }


    private void executeTool(
            PlanStep step,
            AgentContext context,
            List<ToolCallResult> results,
            AgentTool tool
    ) {
        try {
            skillTemplateCoordinator.validateStep(context, step);
            String protectionError = validateFileProtection(step, context);
            if (protectionError != null) {
                log.error("file protection blocked tool={} error={}", step.getTool(), protectionError);
                addFailure(results, context, step, protectionError);
                return;
            }
            long start = System.currentTimeMillis();
            tool.validate(step.getInput());
            Object result = tool.execute(step.getInput(), context);
            long cost = System.currentTimeMillis() - start;
            if (context.getTrace() != null) {
                context.getTrace().add(TraceStep.builder()
                        .type("TOOL")
                        .name(step.getTool())
                        .input(step.getInput())
                        .output(result)
                        .cost(cost)
                        .build());
            }
            ToolCallResult callResult = buildToolResult(step.getTool(), result);
            results.add(callResult);
            context.getObservations().add(Observation.builder()
                    .tool(step.getTool())
                    .success(callResult.isSuccess())
                    .result(result)
                    .input(step.getInput())
                    .error(callResult.isSuccess() ? null : "tool execute failed")
                    .build());
        } catch (Exception e) {
            log.warn("tool execute failed tool={} error={}", step.getTool(), e.getMessage());
            ToolCallResult failed = ToolCallResult.failed(step.getTool(), e.getMessage());
            results.add(failed);
            context.getObservations().add(Observation.builder()
                    .tool(step.getTool())
                    .success(false)
                    .input(step.getInput())
                    .error(e.getMessage())
                    .build());
        }
    }
    private String validateFileProtection(PlanStep step, AgentContext context) {
        if (!(step.getInput() instanceof ToolInput)) {
            return null;
        }
        ToolInput input = (ToolInput) step.getInput();
        String path = input.getPath();
        if (path == null) {
            return null;
        }
        if (("write_file".equals(step.getTool()) || "create_file".equals(step.getTool()))
                && pathExists(path)) {
            return "File Protection: existing file requires read_file then patch_file path=" + path;
        }
        if ("patch_file".equals(step.getTool()) && !pathExists(path)) {
            // 做一次"按文件名唯一匹配"的路径纠正，避免因模型漏写包路径段而误报找不到文件
            String corrected = correctedPath(path);
            if (corrected == null) {
                return "File Protection: patch_file target file not found path=" + path;
            }
            path = corrected;
        }
        if ("patch_file".equals(step.getTool()) && !hasReadFile(path, context)) {
            return "File Protection: patch_file requires read_file first path=" + path;
        }
        return null;
    }

    /**
     * 返回给定路径对应的真实存在文件路径（按文件名唯一匹配纠正），
     * 若原路径不存在也无法纠正则返回 null。
     */
    private String correctedPath(String path) {
        try {
            File file = workspaceService.resolveExistingFile(path);
            if (file == null || !file.exists()) {
                return null;
            }
            return workspaceService.relativePath(file);
        } catch (Exception e) {
            return null;
        }
    }
    private boolean hasReadFile(String path, AgentContext context) {
        if (context.getObservations() == null) {
            return false;
        }
        File target;
        try {
            target = workspaceService.resolve(path);
        } catch (Exception e) {
            // patch_file 目标路径不在 workspace 内，无法校验 read_file 前置条件
            return false;
        }
        return context.getObservations().stream().anyMatch(o -> {
            if (!"read_file".equals(o.getTool())
                    || !o.isSuccess()
                    || !(o.getResult() instanceof FileContent)) {
                return false;
            }
            FileContent content = (FileContent) o.getResult();
            File readFile;
            try {
                readFile = workspaceService.resolve(content.getPath());
            } catch (Exception e) {
                // read_file 结果路径不在 workspace 内（如 agent home docs 参考模板），
                // 该条 observation 不参与 patch_file 前置校验，跳过
                return false;
            }
            return sameFile(target, readFile);
        });
    }

    private boolean shouldSkipDuplicateCreate(PlanStep step, AgentContext context) {
        // create_file 额外检查任务内重复创建；已有文件覆盖由统一文件保护拦截。
        if (!"create_file".equals(step.getTool()) || !(step.getInput() instanceof ToolInput)) {
            return false;
        }
        ToolInput input = (ToolInput) step.getInput();
        String path = input.getPath();
        if (path == null || context.getObservations() == null) {
            return false;
        }
        return context.getObservations().stream().anyMatch(o -> {
            if (!o.isSuccess() || !isCreateTool(o.getTool())) {
                return false;
            }
            String existingPath = extractFileOperationPath(o);
            return existingPath != null && samePath(existingPath, path);
        });
    }

    private String extractFileOperationPath(Observation observation) {
        if (observation.getResult() instanceof FileOperationResult) {
            return ((FileOperationResult) observation.getResult()).getPath();
        }
        if (observation.getInput() instanceof ToolInput) {
            return ((ToolInput) observation.getInput()).getPath();
        }
        return null;
    }
    private ToolCallResult buildToolResult(String tool, Object result) {
        if (result instanceof FileOperationResult) {
            FileOperationResult fileResult = (FileOperationResult) result;
            if (!fileResult.isSuccess()) {
                return ToolCallResult.failed(tool, fileResult.getMessage());
            }
            return ToolCallResult.success(tool, result);
        }
        if (result instanceof Map) {
            Map map = (Map) result;
            Object success = map.get("success");
            if (success instanceof Boolean && !((Boolean) success)) {
                return ToolCallResult.failed(tool, String.valueOf(map.get("message")));
            }
        }
        return ToolCallResult.success(tool, result);
    }

    private boolean isCreateTool(String tool) {
        return "create_file".equals(tool) || "write_file".equals(tool);
    }

    private boolean pathExists(String path) {
        try {
            File file = workspaceService.resolve(path);
            return file.exists() && file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.replace("\\", "/").equals(b.replace("\\", "/"));
    }
}
