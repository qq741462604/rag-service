package com.cmbchina.llm.service;

import com.cmbchina.llm.agent.Agent;
import com.cmbchina.llm.agent.AgentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Agent单任务异步执行器。
 *
 * <p>将单任务 Agent 执行封装为异步调用，避免同步等待导致网关 504 超时。
 * 执行前先将任务标记为 PENDING，执行完毕后根据结果标记为 SUCCESS 或 FAILED，
 * 结果存入 {@link AgentTaskStore} 供调用方轮询查询。</p >
 *
 * @author IT006901
 * @date 2026-09-15 15:30:00
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAsyncExecutor {

    private final Agent agent;

    private final AgentTaskStore taskStore;

    /**
     * 异步执行单个 Agent 任务。
     *
     * <p>使用 {@code agentTaskExecutor} 线程池（核心线程数1，队列容量10），
     * 避免多个 Agent 任务并发调用大模型导致网关超时。</p >
     *
     * @param taskId 任务ID
     * @param task   任务描述
     */
    @Async("agentTaskExecutor")
    public void executeAsync(String taskId, String task) {
        try {
            log.info("agent async task start taskId={} task={}", taskId, task);
            AgentResult result = agent.run(task);
            if (result == null) {
                taskStore.markFailed(taskId, "agent result is null");
                return;
            }
            // 根据 AgentResult 的 success 字段决定标记成功或失败。
            // Agent.run() 的 catch 块会捕获异常并返回 AgentResult.failed()，
            // 此时 result != null 但 success=false，必须标记为 FAILED，
            // 否则日志会显示 "agent async task success" 但查询结果 success=false，造成混淆。
            if (result.isSuccess()) {
                taskStore.markSuccess(taskId, result);
            } else {
                taskStore.markFailed(taskId, result.getMessage());
            }
        } catch (Exception e) {
            log.error("agent async task error taskId={}", taskId, e);
            taskStore.markFailed(taskId, e.getClass().getName() + ": " + e.getMessage());
        }
    }

}
