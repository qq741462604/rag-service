package com.cmbchina.llm.agent.observation;


import lombok.Builder;
import lombok.Data;



@Data
@Builder
public class Observation {


    /**
     * 工具名称
     */
    private String tool;



    /**
     * 是否成功
     */
    private boolean success;



    /**
     * 工具输出
     */
    private Object result;



    /**
     * 错误信息
     */
    private String error;

    private Object input;
}