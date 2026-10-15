package com.cmbchina.llm.context;


import lombok.Builder;
import lombok.Data;



@Data
@Builder
public class ContextItem {


    /**
     * 来源
     */
    private String source;



    /**
     * 内容
     */
    private String content;



    /**
     * 优先级
     */
    private int priority;



    /**
     * token估算
     */
    private int tokens;


}