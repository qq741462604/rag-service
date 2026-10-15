package com.cmbchina.llm.planner;


import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class PlanStep {


    /**
     * 步骤编号
     */
    private Integer order;



    /**
     * 使用的工具
     */
    private String tool;



    /**
     * 工具输入
     */
    private Object input;



    /**
     * 描述
     */
    private String description;


}