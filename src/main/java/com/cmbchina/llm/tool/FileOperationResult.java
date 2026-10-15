package com.cmbchina.llm.tool;


import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class FileOperationResult {


    private String action;


    private String path;


    private boolean success;
    private String message;

}