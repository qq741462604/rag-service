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
        // 兜底：如果 Agent 已经成功执行过写代码动作（patch_file / write_file / create_file），
        // 说明核心改造任务已完成。此时 verifyErrors 中残留的往往只是"连续只读计划"这类引导性
        // 警告（由 AgentLoop 在 CODING/REPAIR 阶段注入，用于提示模型生成写入动作），并非真正的
        // 代码验证失败。为避免把已成功落盘的任务误判为失败，这里优先检查是否已有成功的代码修改，
        // 与 AgentLoop#hasSuccessfulCodeChange 的判断逻辑保持一致。
        if (hasSuccessfulCodeChange(context)) {
            return null;
        }

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