package com.cmbchina.llm.controller.request;

import lombok.Data;

/**
 * 批量执行中的单个需求项。
 *
 * @author yy
 * @date 2026-08-10 11:00:00
 */
@Data
public class AgentTaskRequest {

    /**
     * 需求标识，调用方可用于关联返回结果。
     */
    private String id;

    /**
     * 需求内容。
     */
    private String task;

}
