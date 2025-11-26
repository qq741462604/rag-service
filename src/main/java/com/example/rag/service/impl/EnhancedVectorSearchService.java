package com.example.rag.service.impl;

import com.example.rag.vector.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EnhancedVectorSearchService {

    private final VectorStore vectorStore;

    public EnhancedVectorSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Result> topK(float[] query, int k) {

        int dim = vectorStore.getDimension();

        if (query.length != dim) {
            throw new IllegalArgumentException(
                    "查询向量维度错误！需要 " + dim + " ，但收到 " + query.length);
        }

        query = normalize(query);

        List<Result> results = new ArrayList<>();

        for (Map.Entry<String, float[]> e : vectorStore.getVectors().entrySet()) {
            float score = cosine(query, e.getValue());
            results.add(new Result(e.getKey(), score));
        }

        results.sort((a, b) -> Float.compare(b.score, a.score));
        return results.subList(0, Math.min(k, results.size()));
    }

    private float cosine(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        float norm = (float) Math.sqrt(sum);

        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
        return out;
    }

    public static class Result {
        public String key;
        public float score;

        public Result(String key, float score) {
            this.key = key;
            this.score = score;
        }
    }
}
