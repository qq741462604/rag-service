package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EnhancedVectorSearchService {

    private final KbService kb;

    // 控制混合搜索的权重
    private static final float WEIGHT_ALIAS = 100f;
    private static final float WEIGHT_TEXT = 1.5f;
    private static final float WEIGHT_VECTOR = 60f;


    public EnhancedVectorSearchService(KbService kb) {
        this.kb = kb;
    }

//    @Autowired
//    private SearchLogService logService;

    // ---------- 对外暴露的统一搜索接口 ----------
    public List<SearchResult> hybridSearch(String query, int topK) {

        // 1. alias 命中立即放在最前面（100%置信度）
        List<SearchResult> aliasHits = aliasMatch(query);

        // 2. 文本 + 向量 混合评分
        List<SearchResult> hybridHits = new ArrayList<>();
        for (FieldInfo f : kb.all()) {
            float scoreText = textSimilarity(query, f);
            float scoreVector = vectorSimilarity(query, f);

            float finalScore = scoreText * WEIGHT_TEXT + scoreVector * WEIGHT_VECTOR;

            hybridHits.add(new SearchResult(f, finalScore));
        }

        // 排序
        hybridHits.sort((a, b) -> Float.compare(b.score, a.score));

        // 去重：把 alias 命中过滤掉
        Set<String> aliasFields = new HashSet<>();
        aliasHits.forEach(h -> aliasFields.add(h.info.canonicalField));

        List<SearchResult> merged = new ArrayList<>(aliasHits);
        for (SearchResult r : hybridHits) {
            if (!aliasFields.contains(r.info.canonicalField)) {
                merged.add(r);
            }
        }

        // 返回 topK
        return merged.subList(0, Math.min(topK, merged.size()));
    }

    // ---------------------------------------------------
    // alias 精确匹配
    // ---------------------------------------------------
    private List<SearchResult> aliasMatch(String query) {
        List<SearchResult> results = new ArrayList<>();
        for (FieldInfo f : kb.all()) {
            if (f.aliases != null && f.aliases.contains(query)) {
                results.add(new SearchResult(f, WEIGHT_ALIAS)); // 最高置信度
            }
        }
        return results;
    }

    // ---------------------------------------------------
    // 文本 fuzzy 匹配（对所有 FieldInfo 字段做字符交集）
    // ---------------------------------------------------
    private float textSimilarity(String q, FieldInfo f) {
        String text = (f.aliases + "," + f.canonicalField + "," + f.columnName + "," +
                f.description + "," + f.remark + "," + f.dataType).toLowerCase();

        q = q.toLowerCase();
        int count = 0;
        for (char c : q.toCharArray()) {
            if (text.indexOf(c) >= 0) count++;
        }
        return count;
    }

    // ---------------------------------------------------
    // 向量相似度（如果无 embedding 返回 0）
    // ---------------------------------------------------
    private float vectorSimilarity(String query, FieldInfo f) {
        if (f.embedding == null || f.embedding.length == 0) return 0;

        float[] qvec = EmbeddingCache.get(query);
        if (qvec == null) return 0;

        return cosine(qvec, f.embedding);
    }

    // ---------------------------------------------------
    // 余弦相似度
    // ---------------------------------------------------
    private float cosine(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;

        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }

        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-5));
    }

    // 对外统一返回结构
    public static class SearchResult {
        public final FieldInfo info;
        public final float score;

        public SearchResult(FieldInfo info, float score) {
            this.info = info;
            this.score = score;
        }
    }
}
