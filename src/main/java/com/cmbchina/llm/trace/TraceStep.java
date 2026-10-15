package com.cmbchina.llm.trace;


import lombok.Builder;
import lombok.Data;



@Data
@Builder
public class TraceStep {


    /**
     * 类型
     *
     * PLAN
     * TOOL
     * RESULT
     */
    private String type;



    /**
     * 名称
     */
    private String name;



    /**
     * 输入
     */
    private Object input;



    /**
     * 输出
     */
    private Object output;



    /**
     * 耗时
     */
    private long cost;



}