package com.cmbchina.llm.tool;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolInput {

    /**
     * 文件路径
     */
    private String path;

    /**
     * 搜索关键字
     */
    private String keyword;

    /**
     * 最大数量
     */
    private Integer limit;

    /**
     * 新文件内容
     */
    private String content;

    /**
     * 替换前
     */
    private String oldText;

    /**
     * 替换后
     */
    private String newText;

    private Integer startLine;

    private Integer endLine;
}
