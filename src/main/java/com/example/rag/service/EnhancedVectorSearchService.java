package com.example.rag.service;

import com.example.rag.embedding.EmbeddingService;
import com.example.rag.model.FieldInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class EnhancedVectorSearchService {

    private final KbService kb;
    private final BM25Service bm25;
    private final CorrectionService correction;
    private final SearchLogService logService;
    private final EmbeddingService embeddingService;

    // 控制混合搜索的权重
    private static final float WEIGHT_ALIAS = 100f;
    private static final float WEIGHT_TEXT = 1.5f;
    private static final float WEIGHT_VECTOR = 60f;
    // 权重（可调）
    private static final double W_BM25 = 1.0;
    private static final double W_VECTOR = 0.8;
    private static final double W_TEXT = 0.3;
    private static final double W_PRIORITY = 0.05;
    private static final double ALIAS_BONUS = 2.0;


    public EnhancedVectorSearchService(KbService kb,
                                       BM25Service bm25,
                                       CorrectionService correction,
                                       SearchLogService logService,
                                       EmbeddingService embeddingService) {
        this.kb = kb;
        this.bm25 = bm25;
        this.correction = correction;
        this.logService = logService;
        this.embeddingService = embeddingService;
    }



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

    public List<Result> match(String query, int topK) {
        long start = System.currentTimeMillis();

        // 1) check correction
        String corr = correction.applyCorrection(query);
        if (corr != null) {
            FieldInfo f = kb.getByCanonical(corr);
            if (f != null) {
                // log and return single high-confidence result
                logService.log(query, "CORRECTION_APPLIED -> " + corr);
                return Collections.singletonList(new Result(f, 999.0, 0,0,0));
            }
        }

        // 2) alias exact hit (immediate strong candidate)
        List<Result> aliasResults = new ArrayList<>();
        for (FieldInfo f : kb.all()) {
            if (f.aliases != null && containsTokenExact(f.aliases, query)) {
                aliasResults.add(new Result(f, ALIAS_BONUS, 0,0,0));
            }
        }

        // 3) build candidate set using BM25 candidateDocs (fast)
        Set<String> candidates = bm25.candidateDocs(query);
        // also ensure alias hits included
        for (Result r : aliasResults) candidates.add(r.field.canonicalField);

        // if candidates empty, fallback to all (small KB ok)
        Collection<FieldInfo> pool;
        if (candidates.isEmpty()) {
            pool = kb.all();
        } else {
            pool = candidates.stream()
                    .map(kb::getByCanonical)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        // 4) compute scores for pool
        float[] qVec = embeddingService.embed(query); // may be null
        List<Result> scored = new ArrayList<>();
        for (FieldInfo f : pool) {
            double bm25Score = bm25.score(query, f.canonicalField);
            double vecScore = (qVec != null && f.embedding != null && qVec.length == f.embedding.length)
                    ? cosine(qVec, f.embedding)
                    : 0.0;
            double textScore = simpleTextSim(query, f);
            double total = bm25Score * W_BM25 + vecScore * W_VECTOR + textScore * W_TEXT + f.priorityLevel * W_PRIORITY;

            // if alias exact, add bonus
            if (f.aliases != null && containsTokenExact(f.aliases, query)) total += ALIAS_BONUS;

            scored.add(new Result(f, total, bm25Score, vecScore, textScore));
        }

        // 5) sort
        scored.sort((a,b) -> Double.compare(b.score, a.score));

        // 6) result topK
        List<Result> out = scored.subList(0, Math.min(topK, scored.size()));

        // 7) logging details
        StringBuilder sb = new StringBuilder();
        sb.append("match_time_ms=").append(System.currentTimeMillis() - start).append(";");
        sb.append("candidates=").append(out.size()).append(";");
        for (Result r : out) {
            sb.append("[field=").append(r.field.canonicalField)
                    .append(",score=").append(String.format("%.4f", r.score))
                    .append(",bm25=").append(String.format("%.4f", r.bm25))
                    .append(",vec=").append(String.format("%.4f", r.vec))
                    .append(",text=").append(String.format("%.4f", r.text)).append("]");
        }
        logService.log(query, sb.toString());

        return out;
    }
    // ---------------- utils ----------------
    private boolean containsTokenExact(String aliases, String q) {
        if (aliases == null || q == null) return false;
        for (String a : aliases.split("[,|;]")) {
            if (a.trim().equalsIgnoreCase(q.trim())) return true;
        }
        return false;
    }
    private double simpleTextSim(String q, FieldInfo f) {
        // character overlap normalized
        String text = (f.aliases + " " + f.canonicalField + " " + f.columnName + " " + f.description + " " + f.remark).toLowerCase();
        q = q.toLowerCase();
        int hit = 0;
        for (char c : q.toCharArray()) if (text.indexOf(c) >= 0) hit++;
        return (double) hit / Math.max(1, q.length());
    }

    // result container
    public static class Result {
        public final FieldInfo field;
        public final double score;
        public final double bm25;
        public final double vec;
        public final double text;
        public Result(FieldInfo f, double score, double bm25, double vec, double text) {
            this.field = f; this.score = score; this.bm25 = bm25; this.vec = vec; this.text = text;
        }
    }


}
