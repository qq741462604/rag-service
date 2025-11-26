package com.example.rag.service;

import com.example.rag.embedding.EmbeddingService;
import com.example.rag.model.FieldInfo;
import org.springframework.stereotype.Service;

@Service
public class VectorMatchService1 {

    private final EmbeddingService embedding;
    private final KbService kb;

    public VectorMatchService1(EmbeddingService embedding, KbService kb) {
        this.embedding = embedding;
        this.kb = kb;
    }

    public FieldInfo match(String query) {

        float[] qVec = embedding.embed(query);

        float bestScore = -1;
        FieldInfo best = null;

        for (FieldInfo f : kb.all()) {

            float score = cosine(qVec, f.embedding);

            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }

        // 默认阈值
        if (bestScore < 0.75) {
            return null;
        }

        return best;
    }

    private float cosine(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (float)(dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-6));
    }
}
