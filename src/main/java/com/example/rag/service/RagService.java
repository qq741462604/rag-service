//package com.example.rag.service;
//
//import com.example.rag.model.FieldInfo;
//import com.example.rag.model.RagResult;
//import com.example.rag.util.SimilarityUtil;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//import java.util.*;
//
//@Service
//public class RagService {
//
//    @Autowired
//    private KbService kb;
//
//    @Autowired
//    private EmbeddingClient embeddingClient;
//
//    // thresholds (可根据你业务微调)
//    private static final double STRING_SIMILARITY_THRESHOLD = 0.88;
//    private static final double SEMANTIC_COSINE_THRESHOLD = 0.70;
//
//    /**
//     *                   ┌──────────────────┐
//     *                   │ 输入字段 q        │
//     *                   └────────┬─────────┘
//     *                            ↓
//     *             ┌───────────────────────────────┐
//     *             │ 1. 完全匹配 alias/canonical    │
//     *             └───────────────────────────────┘
//     *                            ↓（找到→返回）
//     *                            ↓（找不到）
//     *             ┌───────────────────────────────┐
//     *             │ 2. 字符串相似度（拼写/缩写）     │
//     *             └───────────────────────────────┘
//     *                            ↓（找到→返回）
//     *                            ↓（找不到）
//     *             ┌───────────────────────────────┐
//     *             │ 3. 语义匹配（embedding 可选）    │
//     *             └───────────────────────────────┘
//     *                            ↓（找到→返回）
//     *                            ↓（找不到）
//     *             ┌───────────────────────────────┐
//     *             │ 4. 返回“未识别字段”＋建议处理     │
//     *             └───────────────────────────────┘
//     * @param query
//     * @return
//     */
//    // 对外调用
//    public RagResult search(String query) {
//        if (query == null || query.trim().isEmpty()) {
//            RagResult r = new RagResult();
//            r.setCanonicalField(null);
//            r.setStatus("EMPTY_QUERY");
//            return r;
//        }
//        String norm = normalize(query);
//
//        // 1. correction
//        Optional<String> corr = kb.lookupCorrection(norm);
//        if (corr.isPresent()) {
//            FieldInfo f = kb.getInfo(corr.get());
//            return buildResult(f, 1.0, "CORRECTION");
//        }
//
//        // 2. exact alias/column/canonical
//        Optional<String> aliasHit = kb.lookupAlias(norm);
//        if (aliasHit.isPresent()) {
//            FieldInfo f = kb.getInfo(aliasHit.get());
//            return buildResult(f, 0.99, "EXACT");
//        }
//
//        // 3. string similarity across aliases + canonical + column
//        double bestScore = 0;
//        FieldInfo best = null;
//        for (FieldInfo f : kb.allFields()) {
//            // canonical
//            double s1 = SimilarityUtil.jaroWinkler(norm, normalize(f.canonicalField));
//            if (s1 > bestScore) { bestScore = s1; best = f; }
//            // column
//            double s2 = SimilarityUtil.jaroWinkler(norm, normalize(f.columnName));
//            if (s2 > bestScore) { bestScore = s2; best = f; }
//            // aliases
//            if (f.aliases != null) {
//                String[] arr = f.aliases.split(",");
//                for (String a : arr) {
//                    double s = SimilarityUtil.jaroWinkler(norm, normalize(a));
//                    if (s > bestScore) { bestScore = s; best = f; }
//                }
//            }
//        }
//        if (best != null && bestScore >= STRING_SIMILARITY_THRESHOLD) {
//            return buildResult(best, bestScore, "STRING_SIMILAR");
//        }
//
//        // 4. semantic embedding (if enabled)
//        float[] qVec = embeddingClient.embed(query);
//        if (qVec != null) {
//            double bestCos = -1;
//            FieldInfo bestSem = null;
//            for (FieldInfo f : kb.allFields()) {
//                if (f.embedding == null) continue;
//                double cos = com.example.rag.service.EmbeddingClient.cosSim(qVec, f.embedding);
//                if (cos > bestCos) { bestCos = cos; bestSem = f; }
//            }
//            if (bestSem != null && bestCos >= SEMANTIC_COSINE_THRESHOLD) {
//                return buildResult(bestSem, bestCos, "SEMANTIC");
//            }
//        }
//
//        // 5. not found
//        RagResult r = new RagResult();
//        r.setCanonicalField(null);
//        r.setConfidence(0);
//        r.setSource("NONE");
//        r.setStatus("NOT_FOUND");
//        r.setDescription("未识别字段");
//        return r;
//    }
//
//    private RagResult buildResult(FieldInfo f, double score, String source) {
//        RagResult r = new RagResult();
//        r.setCanonicalField(f.canonicalField);
//        r.setConfidence(score);
//        r.setSource(source);
//        r.setMatchedAlias(f.aliases);
//        r.setDescription(f.description);
//        r.setStatus(source);
//        return r;
//    }
//
//    private String normalize(String s) {
//        if (s == null) return "";
//        return s.trim().toLowerCase().replaceAll("[_\\s]", "");
//    }
//}
