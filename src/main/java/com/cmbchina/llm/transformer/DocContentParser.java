package com.cmbchina.llm.transformer;

import com.cmbchina.llm.entity.model.LlmCleanResult;
import com.cmbchina.llm.util.LlmOutputCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DocContentParser {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 从 Qwen chat.completion 中抽出 assistant.content
     */
    public String extractContent(String llmResponse) throws Exception {
        JsonNode root = mapper.readTree(llmResponse);
        String rawContent = root
                .path("choices")
                .get(0)
                .path("message")
                .path("content")
                .asText();
        LlmCleanResult cleanResult = LlmOutputCleaner.clean(rawContent);

        // ✅ 1. 打印 think（仅日志，不入业务）
        if (cleanResult.getThink() != null) {
            log.info("LLM THINK:\n{}", cleanResult.getThink());
        }
        return cleanResult.getJson();
    }
}
