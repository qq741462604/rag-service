package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MatchService {

    @Autowired
    private KbService kb;

    @Autowired
    private EmbeddingClient embeddingClient;

    // thresholds
    private static final double COSINE_THRESHOLD = 0.60;

    public FieldInfo match(String query) {
        if (query == null || query.trim().isEmpty()) return null;

        // 1) exact alias
        java.util.Optional<String> alias = kb.lookupAlias(query);
        if (alias.isPresent()) {
            return kb.get(alias.get());
        }

        // 2) compute query vector
        float[] qv = embeddingClient.embed(query);
        if (qv == null) return null;

        // 3) brute-force cosine over in-memory embeddings
        double best = -2;
        FieldInfo bestF = null;
        for (FieldInfo f : kb.all()) {
            if (f.embedding == null) continue;
            double cos = cosine(qv, f.embedding);
            // optionally weight by priorityLevel
            double score = cos + (f.priorityLevel * 0.01);
            if (score > best) {
                best = score;
                bestF = f;
            }
        }

        if (best >= COSINE_THRESHOLD) return bestF;
        return null;
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -2;
        double dot = 0, na=0, nb=0;
        for (int i = 0; i < a.length; i++) {
            dot += ((double)a[i]) * ((double)b[i]);
            na += ((double)a[i]) * ((double)a[i]);
            nb += ((double)b[i]) * ((double)b[i]);
        }
        if (na == 0 || nb == 0) return -2;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
