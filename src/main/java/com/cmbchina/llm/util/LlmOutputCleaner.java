package com.cmbchina.llm.util;

import com.cmbchina.llm.entity.model.LlmCleanResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description: some desc
 * @author: IT006901
 * @date: 2025/12/19
 */
public class LlmOutputCleaner {

    private LlmOutputCleaner() {
    }

    /** 匹配 <think>...</think>（跨行） */
    private static final Pattern THINK_PATTERN =
            Pattern.compile("(?s)<think>(.*?)</think>");

    /**
     * 清洗 LLM 输出：
     * 1. 提取 think
     * 2. 剥离 think
     * 3. 提取 JSON
     */
    public static LlmCleanResult clean(String rawContent) {

        if (rawContent == null) {
            return new LlmCleanResult(null, null, null);
        }

        String think = null;
        String withoutThink = rawContent;

        // 1⃣ 提取 think
        Matcher matcher = THINK_PATTERN.matcher(rawContent);
        if (matcher.find()) {
            think = matcher.group(1).trim();
            withoutThink = matcher.replaceAll("").trim();
        }

        // 2⃣ 提取 JSON 主体
        String json = extractJson(withoutThink);

        return new LlmCleanResult(rawContent, think, json);
    }

    /**
     * 从文本中提取最外层 JSON
     */
    private static String extractJson(String text) {
        if (text == null) {
            return null;
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return null;
    }
}
