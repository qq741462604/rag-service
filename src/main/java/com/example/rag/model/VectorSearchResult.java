package com.example.rag.model;

import lombok.Data;

import java.util.Map;

@Data
public class VectorSearchResult {
    private final String documentId;
    private final double score;        // 相似度得分
    private final String content;      // 文本内容
    private final Map<String, Object> metadata;

    public VectorSearchResult(String documentId, double score, String content, Map<String, Object> metadata) {
        this.documentId = documentId;
        this.score = score;
        this.content = content;
        this.metadata = metadata;
    }

    // getters...
}
