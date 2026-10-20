package com.cmbchina.llm.runtime;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentResult;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.agent.result.CodeChange;
import com.cmbchina.llm.agent.result.CodeChangeExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecutor {

    private final AgentLoop agentLoop;
    private final CodeChangeExtractor extractor;

    public AgentResult execute(AgentContext context) {
        try {
            agentLoop.run(context);
            List<CodeChange> changes = extractor.extract(context.getObservations());
            String failure = findFailure(context);
            if (failure != null) {
                return AgentResult.builder()
                        .success(false)
                        .message(failure)
                        .data(context.getObservations())
                        .changes(changes)
                        .build();
            }
            return AgentResult.builder()
                    .success(true)
                    .message("agent execute success")
                    .data(context.getObservations())
                    .changes(changes)
                    .build();
        } catch (Exception e) {
            log.error("agent executor error", e);
            return AgentResult.failed(e.toString());
        }
    }

    private String findFailure(AgentContext context) {
        if (context.getVerifyErrors() != null && !context.getVerifyErrors().isEmpty()) {
            return "verify failed: " + String.join("; ", context.getVerifyErrors());
        }

        if (context.getLastObservations() == null || context.getLastObservations().isEmpty()) {
            return null;
        }

        for (Observation observation : context.getLastObservations()) {
            if (!observation.isSuccess()) {
                return "tool failed: "
                        + observation.getTool()
                        + " "
                        + observation.getError();
            }
        }

        return null;
    }

    /**
     * 判断 Agent 执行历史中是否已存在成功的写代码动作。
     *
     * <p>遍历全部观察记录，只要存在 patch_file / write_file / create_file 工具成功执行的记录，
     * 即认为核心代码改造已完成。该判断用于避免把残留的引导性 verifyErrors 误判为任务失败。</p >
     *
     * @param context Agent 上下文
     * @return 若存在成功写代码动作返回 true
     */
    private boolean hasSuccessfulCodeChange(AgentContext context) {
        List<Observation> observations = context.getObservations();
        if (observations == null || observations.isEmpty()) {
            return false;
        }
        for (Observation o : observations) {
            if (o == null || o.getTool() == null || !o.isSuccess()) {
                continue;
            }
            String tool = o.getTool();
            if ("patch_file".equals(tool)
                    || "write_file".equals(tool)
                    || "create_file".equals(tool)) {
                return true;
            }
        }
        return false;
    }

}
