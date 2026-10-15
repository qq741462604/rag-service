package com.cmbchina.llm.agent.result;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批量执行中的单个需求执行结果。
 *
 * @author yy
 * @date 2026-08-10 11:00:00
 */
@Data
@Builder
public class AgentTaskResult {

    /**
     * 需求序号，从 1 开始。
     */
    private int index;

    /**
     * 需求标识。
     */
    private String id;

    /**
     * 需求内容。
     */
    private String task;

    /**
     * 是否执行成功。
     */
    private boolean success;

    /**
     * 执行消息或失败原因。
     */
    private String message;

    /**
     * 代码变更列表。
     */
    private List<CodeChange> changes;

}
