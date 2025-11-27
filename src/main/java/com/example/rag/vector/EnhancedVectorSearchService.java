//package com.example.rag.vector;
//
//import com.example.rag.embedding.EmbeddingService;
//import com.example.rag.model.FieldInfo;
//import com.example.rag.service.KbService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.*;
//
//@Service
//public class EnhancedVectorSearchService {
//
//    private static final int DIM = 1536;
//
//    @Autowired
//    private KbService kbService;
//
//    @Autowired
//    private EmbeddingService embeddingService;
//
//    // 主入口：带 explain
//    public MatchResult match(String query, int topK) {
//
//        float[] qVec = null;
//        if ((qVec = tryEmbed(query)) == null) {
//            return new MatchResult("embedding fail");
//        }
//
//        normalize(qVec);
//
//        PriorityQueue<ScoredField> pq = new PriorityQueue<>(Comparator.comparingDouble(s -> -s.score));
//
//        for (FieldInfo f : kbService.all()) {
//            double semScore = (f.embedding != null) ? cosine(qVec, f.embedding) : 0;
//            double textScore = fuzzyScore(query, f);
//            double finalScore = semScore * 0.7 + textScore * 0.3 + f.priorityLevel * 0.05;
//
//            pq.add(new ScoredField(f, finalScore, semScore, textScore));
//        }
//
//        List<ScoredField> top = new ArrayList<>();
//        for (int i = 0; i < topK && !pq.isEmpty(); i++) top.add(pq.poll());
//
//        return new MatchResult(query, top);
//    }
//
//
//    /** 尝试获取向量（调你已有的服务） */
//    private float[] tryEmbed(String text) {
//        try {
//            return embeddingService.embed(text);
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//
//    /** 文本相似度：别名、canonical、columnName 三位一体 */
//    private double fuzzyScore(String q, FieldInfo f) {
//        q = q.toLowerCase();
//        String text = (f.aliases + "," + f.canonicalField + "," + f.columnName).toLowerCase();
//
//        int hit = 0;
//        for (char c : q.toCharArray()) {
//            if (text.indexOf(c) >= 0) hit++;
//        }
//
//        return hit / (double) q.length();
//    }
//
//
//    /** cosine */
//    private double cosine(float[] a, float[] b) {
//        float dot = 0, na = 0, nb = 0;
//        for (int i = 0; i < DIM; i++) {
//            dot += a[i] * b[i];
//            na += a[i] * a[i];
//            nb += b[i] * b[i];
//        }
//        return dot / (Math.sqrt(na) * Math.sqrt(nb));
//    }
//
//    private void normalize(float[] v) {
//        float sum = 0;
//        for (float x : v) sum += x * x;
//        float d = (float) Math.sqrt(sum);
//        for (int i = 0; i < v.length; i++) v[i] /= d;
//    }
//
//
//    // 返回结构
//    public static class MatchResult {
//        public String query;
//        public List<ScoredField> candidates;
//        public String error;
//
//        public MatchResult(String error) {
//            this.error = error;
//        }
//        public MatchResult(String q, List<ScoredField> cs) {
//            this.query = q;
//            this.candidates = cs;
//        }
//    }
//
//    public static class ScoredField {
//        public FieldInfo field;
//        public double finalScore;
//        public double semScore;
//        public double textScore;
//
//        public ScoredField(FieldInfo f, double fs, double ss, double ts) {
//            this.field = f;
//            this.finalScore = fs;
//            this.semScore = ss;
//            this.textScore = ts;
//        }
//    }
//}
