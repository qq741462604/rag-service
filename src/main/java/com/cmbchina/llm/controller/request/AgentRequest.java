package com.cmbchina.llm.controller.request;


import lombok.Data;

import java.util.List;



@Data
public class AgentRequest {


    /**
     * 单条需求内容。
     */
    private String task;

    /**
     * 批量需求列表。
     */
    private List<AgentTaskRequest> tasks;


}
