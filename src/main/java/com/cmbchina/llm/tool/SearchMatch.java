package com.cmbchina.llm.tool;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 代码搜索结果。
 *
 * <p>当按类名搜索时，会命中两类文件：</p >
 * <ul>
 *   <li>目标类定义本身（如 {@code class DataPrepEventHandler}），matchType=definition</li>
 *   <li>引用目标类的地方（如 {@code new DataPrepEventHandler()}），matchType=reference</li>
 * </ul>
 *
 * <p>通过 matchType 帮助 LLM 在 ANALYSIS 阶段准确区分"目标类本身"与"引用目标类的位置"，
 * 避免误把引用类当作修改入口。</p >
 *
 * @author IT006901
 * @date 2026-09-03 15:30:00
 */
@Data
@Builder
public class SearchMatch {

    /**
     * 文件相对路径
     */
    private String path;

    /**
     * 命中行号
     */
    private Integer lineNumber;

    /**
     * 命中行内容
     */
    private String content;

    /**
     * 上下文行
     */
    private List<String> context;

    /**
     * 相对路径（冗余字段，保留兼容）
     */
    private String relativePath;

    /**
     * 匹配类型：
     * <ul>
     *   <li>definition —— 本文件包含目标类/接口的定义本身（class/interface 声明）</li>
     *   <li>reference —— 本文件仅引用了目标类（如 new 实例化、作为类型使用）</li>
     * </ul>
     */
    private String matchType;
}
