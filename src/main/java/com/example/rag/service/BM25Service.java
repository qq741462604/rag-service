package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 简单 BM25 实现（用于文本相关性召回）
 *
 * 使用方式：
 *  - 在 KbService load 完成时调用 buildIndex(Collection<FieldInfo>)
 *  - 调用 score(query, canonicalField) 获取 BM25 得分
 */
@Service
public class BM25Service {

    private final Map<String, Map<String, Integer>> tf = new HashMap<>(); // doc -> term -> freq
    private final Map<String, Integer> docLen = new HashMap<>(); // doc -> length
    private final Map<String, Integer> df = new HashMap<>(); // term -> document frequency
    private final Map<String, Set<String>> inverted = new HashMap<>(); // term -> docs
    private double avgDocLen = 0;
    private int totalDocs = 0;

    // BM25 params
    private static final double k1 = 1.5;
    private static final double b = 0.75;


    private List<FieldInfo> kb;
    private List<List<String>> documents;
    private double avgDocLength;
    public void build(List<FieldInfo> all) {
        this.kb = all;

        documents = new ArrayList<>();
        df.clear();

        int totalLength = 0;

        for (FieldInfo f : all) {
            List<String> tokens = tokenize(f.aliases + " " + f.canonicalField + " " + f.description);
            documents.add(tokens);

            totalLength += tokens.size();

            tokens.stream().distinct().forEach(term ->
                    df.put(term, df.getOrDefault(term, 0) + 1)
            );
        }
        avgDocLength = totalLength * 1.0 / documents.size();
    }

    public Map<FieldInfo, Double> search(String query) {

        List<String> qTokens = tokenize(query);
        Map<FieldInfo, Double> scores = new HashMap<>();

        for (int i = 0; i < kb.size(); i++) {
            scores.put(kb.get(i), scoreBM25(qTokens, documents.get(i)));
        }
        return scores;
    }
    private double scoreBM25(List<String> qTokens, List<String> docTokens) {
        double score = 0.0;

        for (String t : qTokens) {
            score += bm25Term(t, docTokens);
        }
        return score;
    }
    private double bm25Term(String term, List<String> doc) {
        int N = documents.size();
        int dfTerm = df.getOrDefault(term, 0);
        if (dfTerm == 0) return 0;

        long tf = doc.stream().filter(t -> t.equals(term)).count();

        double idf = Math.log(1 + (N - dfTerm + 0.5) / (dfTerm + 0.5));
        double k1 = 1.5;
        double b = 0.75;

        return idf * (tf * (k1 + 1)) /
                (tf + k1 * (1 - b + b * doc.size() / avgDocLength));
    }
    /**
     * 构建索引，覆盖旧索引
     */
    public synchronized void buildIndex(Collection<FieldInfo> fields) {
        tf.clear(); df.clear(); inverted.clear(); docLen.clear();
        totalDocs = 0;

        for (FieldInfo f : fields) {
            String docId = f.canonicalField;
            String text = buildDocText(f);
            List<String> tokens = tokenize(text);

            totalDocs++;
            docLen.put(docId, tokens.size());

            Map<String, Integer> docTf = tf.computeIfAbsent(docId, k -> new HashMap<>());
            Set<String> seen = new HashSet<>();

            for (String t : tokens) {
                docTf.put(t, docTf.getOrDefault(t, 0) + 1);
                if (!seen.contains(t)) {
                    df.put(t, df.getOrDefault(t, 0) + 1);
                    inverted.computeIfAbsent(t, k -> new HashSet<>()).add(docId);
                    seen.add(t);
                }
            }
        }

        // avg doc len
        long sum = 0;
        for (int l : docLen.values()) sum += l;
        avgDocLen = totalDocs == 0 ? 0 : (double) sum / totalDocs;
    }

    private String buildDocText(FieldInfo f) {
        StringBuilder sb = new StringBuilder();
        if (f.canonicalField != null) sb.append(f.canonicalField).append(" ");
        if (f.columnName != null) sb.append(f.columnName).append(" ");
        if (f.aliases != null) sb.append(f.aliases).append(" ");
        if (f.description != null) sb.append(f.description).append(" ");
        if (f.remark != null) sb.append(f.remark).append(" ");
        return sb.toString();
    }

    private List<String> tokenize(String text) {
        if (text == null) return Collections.emptyList();
        String[] parts = text.toLowerCase().split("[^a-z0-9\u4e00-\u9fa5]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.length() >= 1) out.add(t);
        }
        return out;
    }

    /**
     * 查询单个 doc 的 BM25 分数
     */
    public double score(String query, String docId) {
        if (!tf.containsKey(docId)) return 0.0;
        List<String> qTerms = tokenize(query);
        if (qTerms.isEmpty()) return 0.0;

        double score = 0.0;
        for (String qt : qTerms) {
            Integer docFreq = df.get(qt);
            if (docFreq == null) continue;
            int docTermFreq = tf.get(docId).getOrDefault(qt, 0);
            double idf = Math.log( (totalDocs - docFreq + 0.5) / (docFreq + 0.5) + 1.0 );
            double denom = docTermFreq + k1 * (1 - b + b * docLen.get(docId) / avgDocLen);
            double tfWeight = (docTermFreq * (k1 + 1)) / (denom + 1e-9);
            score += idf * tfWeight;
        }
        return score;
    }

    /**
     * 快速获取候选 doc 列表（合并所有 query token 对应倒排）
     */
    public Set<String> candidateDocs(String query) {
        Set<String> out = new HashSet<>();
        for (String t : tokenize(query)) {
            Set<String> s = inverted.get(t);
            if (s != null) out.addAll(s);
        }
        return out;
    }
}
