package com.cmbchina.llm.entity.model;

/**
 * @description: some desc
 * @author: IT006901
 * @date: 2025/12/19
 */
public class LlmCleanResult {

    /** 原始 LLM 输出 */
    private String raw;

    /** 提取出的 think 内容（可能为空） */
    private String think;

    /** 可安全反序列化的 JSON 内容 */
    private String json;

    public LlmCleanResult(String raw, String think, String json) {
        this.raw = raw;
        this.think = think;
        this.json = json;
    }

    public String getRaw() {
        return raw;
    }

    public String getThink() {
        return think;
    }

    public String getJson() {
        return json;
    }
}
