package com.cmbchina.llm.verify;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.runtime.ToolRegistry;
import com.cmbchina.llm.tool.AgentTool;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyFileLoader {

    private final ToolRegistry toolRegistry;
    private final WorkspaceService workspaceService;

    public void loadChangedFiles(AgentContext context) {
        AgentTool readTool = toolRegistry.getTool("read_file");
        if (readTool == null) {
            throw new IllegalStateException("read_file tool not found");
        }

        Set<String> paths = extractPaths(context);
        for (String path : paths) {
            try {
                ToolInput input = ToolInput.builder()
                        .path(path)
                        .startLine(1)
                        .endLine(10000)
                        .build();
                Object result = readTool.execute(input, context);
                context.getObservations().add(
                        Observation.builder()
                                .tool("read_file")
                                .success(true)
                                .input(input)
                                .result(result)
                                .build()
                );
            } catch (Exception e) {
                log.error("verify read file failed path={}", path, e);
                throw new IllegalStateException("验证读取最新文件失败: " + path, e);
            }
        }
    }

    private Set<String> extractPaths(AgentContext context) {
        Set<String> paths = new HashSet<>();
        for (Observation observation : context.getObservations()) {
            if (!isChangedFileObservation(observation) || observation.getResult() == null) {
                continue;
            }
            String path = null;
            if (observation.getResult() instanceof FileOperationResult) {
                path = ((FileOperationResult) observation.getResult()).getPath();
            } else if (observation.getResult() instanceof FileContent) {
                path = ((FileContent) observation.getResult()).getPath();
            } else if (observation.getInput() instanceof ToolInput) {
                path = ((ToolInput) observation.getInput()).getPath();
            } else if (observation.getInput() instanceof java.util.Map) {
                Object value = ((java.util.Map<?, ?>) observation.getInput()).get("path");
                path = value instanceof String ? (String) value : null;
            }
            if (path != null) {
                paths.add(workspaceService.relativePath(workspaceService.resolve(path)));
            }
        }
        return paths;
    }

    private boolean isChangedFileObservation(Observation observation) {
        if (!observation.isSuccess()) {
            return false;
        }
        String tool = observation.getTool();
        return "create_file".equals(tool)
                || "write_file".equals(tool)
                || "edit_file".equals(tool)
                || "patch_file".equals(tool);
    }
}
