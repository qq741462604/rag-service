package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.springframework.stereotype.Service;

@Service
public class MatchService {

    private final KbService kb;
    private final EmbeddingClient embedding;

    public MatchService(KbService kb, EmbeddingClient embedding) {
        this.kb = kb;
        this.embedding = embedding;
    }

    public FieldInfo match(String query) {

        float[] queryVec = embedding.embed(query);

        double best = -1;
        FieldInfo bestF = null;

        for (FieldInfo f : kb.all()) {
            double score = cosine(queryVec, f.embedding);
            if (score > best) {
                best = score;
                bestF = f;
            }
        }

        // 阈值可调整
        return best < 0.60 ? null : bestF;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
