package com.cmbchina.llm.agent;


import com.cmbchina.llm.agent.result.CodeChange;
import lombok.Builder;
import lombok.Data;

import java.util.List;


@Data
@Builder
public class AgentResult {


    private boolean success;


    private String message;


    private Object data;

    private List<CodeChange> changes;
    public static AgentResult success(String message){

        return AgentResult.builder()
                .success(true)
                .message(message)
                .build();
    }


    public static AgentResult failed(String message){

        return AgentResult.builder()
                .success(false)
                .message(message)
                .build();
    }

}