package com.example.rag.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;

import java.util.*;

public class EmbeddingTest {
    private String apiKey;
    // 2. 创建 TextEmbedding 实例
    TextEmbedding textEmbedding = new TextEmbedding();


    /**
     * 获取单个文本的嵌入向量
     */
    public List<Double> getEmbedding(String text) throws Exception {
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(TextEmbedding.Models.TEXT_EMBEDDING_V2)
                .texts(Arrays.asList(text))
                .build();


        TextEmbeddingResult result = textEmbedding.call(param);

        if (result.getOutput() != null &&
                !result.getOutput().getEmbeddings().isEmpty()) {
            return result.getOutput().getEmbeddings().get(0).getEmbedding();
        }

        return Collections.emptyList();
    }

    /**
     * 批量获取嵌入向量
     */
    public Map<String, List<Double>> getBatchEmbeddings(List<String> texts) throws Exception {
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(TextEmbedding.Models.TEXT_EMBEDDING_V2)
                .texts(texts)
                .build();

        TextEmbeddingResult result = textEmbedding.call(param);
        Map<String, List<Double>> embeddings = new HashMap<>();

        if (result.getOutput() != null) {
            List<TextEmbeddingResultItem> items = result.getOutput().getEmbeddings();
            for (int i = 0; i < items.size(); i++) {
                embeddings.put(texts.get(i), items.get(i).getEmbedding());
            }
        }

        return embeddings;
    }

    /**
     * 计算余弦相似度
     */
    public double cosineSimilarity(List<Float> vec1, List<Float> vec2) {
        if (vec1.size() != vec2.size()) {
            throw new IllegalArgumentException("Vectors must have same dimension");
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.size(); i++) {
            dotProduct += vec1.get(i) * vec2.get(i);
            norm1 += Math.pow(vec1.get(i), 2);
            norm2 += Math.pow(vec2.get(i), 2);
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
