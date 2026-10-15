package com.cmbchina.llm.runtime;


import lombok.Builder;
import lombok.Data;



@Data
@Builder
public class ToolCallResult {


    private String tool;


    private boolean success;


    private Object result;


    private String error;




    public static ToolCallResult success(
            String tool,
            Object result
    ){

        return builder()
                .tool(tool)
                .success(true)
                .result(result)
                .build();

    }




    public static ToolCallResult failed(
            String tool,
            String error
    ){

        return builder()
                .tool(tool)
                .success(false)
                .error(error)
                .build();

    }


}