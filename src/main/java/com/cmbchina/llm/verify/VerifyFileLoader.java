package com.cmbchina.llm.verify;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.runtime.ToolRegistry;
import com.cmbchina.llm.tool.AgentTool;
import com.cmbchina.llm.tool.ToolInput;
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

    public void loadChangedFiles(AgentContext context) {
        AgentTool readTool = toolRegistry.getTool("read_file");
        if (readTool == null) {
            log.warn("read_file tool not found");
            return;
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
            }
        }
    }

    private Set<String> extractPaths(AgentContext context) {
        Set<String> paths = new HashSet<>();
        for (Observation observation : context.getObservations()) {
            if (!isChangedFileObservation(observation) || observation.getResult() == null) {
                continue;
            }
            String text = observation.getResult().toString();
            if (text.contains("path=")) {
                int start = text.indexOf("path=") + 5;
                int end = text.indexOf(",", start);
                if (end < 0) {
                    end = text.length();
                }
                paths.add(text.substring(start, end));
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
