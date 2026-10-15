package com.cmbchina.llm.agent.result;


import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class CodeChange {


    /**
     * 操作类型
     *
     * create
     * edit
     * delete
     */
    private String action;



    /**
     * 文件路径
     */
    private String path;



    /**
     * 是否成功
     */
    private boolean success;


}