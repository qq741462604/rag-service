package com.cmbchina.llm.agent.result;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批量需求执行汇总结果。
 *
 * @author yy
 * @date 2026-08-10 11:00:00
 */
@Data
@Builder
public class AgentBatchResult {

    /**
     * 是否因失败而停止后续任务。
     */
    private boolean stopped;

    /**
     * 失败任务序号，从 1 开始。
     */
    private Integer failedIndex;

    /**
     * 失败任务标识。
     */
    private String failedTaskId;

    /**
     * 失败原因。
     */
    private String failureReason;

    /**
     * 已执行任务结果。
     */
    private List<AgentTaskResult> results;

}
