package com.cmbchina.llm.agent;


public enum AgentPhase {


    /**
     * 分析项目阶段
     */
    ANALYSIS,


    /**
     * 代码生成阶段
     */
    CODING,


    /**
     * 验证阶段
     */
    VERIFY,


    /**
     * 修复阶段
     */
    REPAIR,


    /**
     * 完成
     */
    FINISH

}