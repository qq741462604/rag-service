package com.cmbchina.llm.tool;


import lombok.Builder;
import lombok.Data;

import java.util.List;



@Data
@Builder
public class FileContent {


    /**
     * 文件路径
     */
    private String path;



    /**
     * 开始行
     */
    private Integer startLine;



    /**
     * 结束行
     */
    private Integer endLine;



    /**
     * 文件内容
     */
    private List<String> lines;


}