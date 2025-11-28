package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.example.rag.util.VectorUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class SmartCorrectionService {

    private final KbService kb;
    private final EmbeddingService embeddingService;

    // alias → canonical
    private final Map<String, String> aliasIndex = new HashMap<>();

    // embedding cache（alias embedding）
    private final Map<String, float[]> aliasVecCache = new HashMap<>();

    private final Pattern cleanPattern = Pattern.compile(
            "(是什么|是啥|多少|一下|请问|呢|吗|的|一下|下)$"
    );

    public SmartCorrectionService(KbService kb, EmbeddingService embeddingService) {
        this.kb = kb;
        this.embeddingService = embeddingService;
        init();
    }

    private void init() {
        for (FieldInfo f : kb.all()) {
            List<String> names = new ArrayList<>();

            // canonical name
            names.add(f.getCanonicalField());

            // columnName
            if (f.getColumnName() != null)
                names.add(f.getColumnName());

            // aliases
            if (f.getAliases() != null)
                names.addAll(Arrays.asList(f.getAliases().split("[,|;]")));

            // index
            for (String n : names) {
                String key = normalize(n);
                aliasIndex.put(key, f.getCanonicalField());
                aliasVecCache.put(key, embed(n));  // embedding cache
            }
        }
    }

    /** 对输入做智能纠错 → 返回 canonicalField */
    public String resolve(String text) {
        if (text == null || text.isEmpty()) return null;

        String cleaned = clean(text);
        String key = normalize(cleaned);

        // 1. exact match
        if (aliasIndex.containsKey(key))
            return aliasIndex.get(key);

        // 2. contains match
        for (String a : aliasIndex.keySet()) {
            if (key.contains(a) || a.contains(key)) {
                return aliasIndex.get(a);
            }
        }

        // 3. embedding semantic match
        float[] q = embed(cleaned);
        if (q == null) return null;

        q = VectorUtils.normalize(q);
        double bestScore = 0.0;
        String bestCanonical = null;

        for (Map.Entry<String, float[]> e : aliasVecCache.entrySet()) {
            float[] v = e.getValue();
            if (v == null) continue;

            double s = VectorUtils.cosine(q, v);
            if (s > bestScore) {
                bestScore = s;
                bestCanonical = aliasIndex.get(e.getKey());
            }
        }

        // 收敛阈值（通常 0.5 合理）
        return (bestScore > 0.50) ? bestCanonical : null;
    }

    /** 清理尾部问句修饰 */
    private String clean(String s) {
        s = s.trim();
        return cleanPattern.matcher(s).replaceAll("");
    }

    private String normalize(String s) {
        return s.trim().toLowerCase().replaceAll("[_\\s]", "");
    }

    private float[] embed(String t) {
        try {
            float[] v = embeddingService.embed(t);
            if (v == null) return null;
            return VectorUtils.normalize(v);
        } catch (Exception e) {
            return null;
        }
    }
}
