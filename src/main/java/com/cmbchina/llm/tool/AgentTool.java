package com.cmbchina.llm.tool;

import com.cmbchina.llm.agent.AgentContext;

public interface AgentTool {

    /**
     * 工具名称
     */
    String name();

    /**
     * 工具描述
     */
    String description();

    /**
     * 执行工具
     */
    Object execute(Object input, AgentContext context) throws Exception;

    default void validate(Object input) {
    }
}
