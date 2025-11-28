package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.example.rag.service.CorrectionService;
import com.example.rag.service.EmbeddingService;
import com.example.rag.service.KbService;
import com.example.rag.util.VectorUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EnhancedVectorSearchService {

    private final KbService kb;
    private final EmbeddingService embeddingService;
    private final CorrectionService correctionService;

    @Autowired
    private SmartCorrectionService smartCorrectionService;

    @Value("${search.desc-weight}")
    private int descWeight;

    @Value("${search.name-weight}")
    private int nameWeight;

    public EnhancedVectorSearchService(
            KbService kb,
            EmbeddingService embeddingService,
            CorrectionService correctionService) {
        this.kb = kb;
        this.embeddingService = embeddingService;
        this.correctionService = correctionService;
    }


    /** 三方融合搜索 */
    public List<Hit> matchJsonFields(List<Map<String, String>> input, int topK) {

        List<Hit> result = new ArrayList<>();

        for (Map<String, String> item : input) {

            String name = item.getOrDefault("name", "");
            String desc = item.getOrDefault("description", "");
            String weighted = buildWeightedText(name, desc);

            List<Candidate> candidates = new ArrayList<>();

            // -----------------------------------------
            // 1. correction（最高优先级）
            // -----------------------------------------
//            String corr = correctionService.getCorrection(desc);
            // 改成智能纠错
            String corr = smartCorrectionService.resolve(desc);
            if (corr != null) {
                FieldInfo f = kb.getByCanonical(corr);
                if (f != null) {
                    candidates.add(new Candidate(f, 1.0, "correction"));
                    result.add(Hit.fromCandidates(name, desc, candidates));
                    continue;
                }
            }

            // -----------------------------------------
            // 2. alias（第二优先级）
            // -----------------------------------------
            FieldInfo alias = resolveAliasMatch(desc, name);
            if (alias != null) {
                candidates.add(new Candidate(alias, 0.95, "alias"));
                result.add(Hit.fromCandidates(name, desc, candidates));
                continue;
            }

            // -----------------------------------------
            // 3. vector（核心）
            // -----------------------------------------
            float[] qVec = embeddingService.embed(weighted);
            if (qVec != null) {
                qVec = VectorUtils.normalize(qVec);
                List<Scored> vecTop = vectorTopK(qVec, Math.max(topK, 10));
                for (Scored s : vecTop) {
                    candidates.add(new Candidate(s.field, s.score * 0.9, "vector"));
                }
            }

            // -----------------------------------------
            // 4. fuzzy（兜底）
            // -----------------------------------------
            FieldInfo fuzzy = fuzzyBest(name, desc);
            if (fuzzy != null) {
                double fuzzyScore = simpleFuzzy(desc.isEmpty() ? name : desc, fuzzy);
                candidates.add(new Candidate(fuzzy, fuzzyScore * 0.5, "fuzzy"));
            }

            // -----------------------------------------
            // 5. 最终融合决策
            // -----------------------------------------
            if (candidates.isEmpty()) {
                candidates.add(new Candidate(null, 0, "none"));
            }

            result.add(Hit.fromCandidates(name, desc, candidates));
        }

        return result;
    }


    // ---------------- helpers ---------------------

    private FieldInfo resolveAliasMatch(String desc, String name) {
        FieldInfo f = resolveAlias(desc);
        return (f != null) ? f : resolveAlias(name);
    }

    private FieldInfo resolveAlias(String q) {
        String key = normalize(q);
        String canonical = kb.resolveAlias(key);
        return canonical != null ? kb.getByCanonical(canonical) : null;
    }

    private String buildWeightedText(String name, String desc) {
        StringBuilder sb = new StringBuilder();
        if (!desc.isEmpty())
//            sb.append(desc.repeat(descWeight)).append(" ");
            sb.append(repeat(desc,descWeight)).append(" ");
        if (!name.isEmpty())
//            sb.append(name.repeat(nameWeight)).append(" ");
            sb.append(repeat(name,nameWeight)).append(" ");
        return sb.toString().trim();
    }

    private List<Scored> vectorTopK(float[] qVec, int k) {
        List<Scored> list = new ArrayList<>();
        for (FieldInfo f : kb.all()) {
            if (f.getEmbedding() == null) continue;
            double score = VectorUtils.cosine(qVec, f.getEmbedding());
            list.add(new Scored(f, score));
        }
        list.sort((a, b) -> Double.compare(b.score, a.score));
        return list.subList(0, Math.min(k, list.size()));
    }

    // 自定义 repeat 方法
    public static String repeat(String str, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count 不能为负数: " + count);
        }
        return new String(new char[count]).replace("\0", str);
    }

    private FieldInfo fuzzyBest(String name, String desc) {
        double best = -1;
        FieldInfo bestF = null;
        String q = (desc.isEmpty() ? name : desc).toLowerCase();
        for (FieldInfo f : kb.all()) {
            double s = simpleFuzzy(q, f);
            if (s > best) {
                best = s;
                bestF = f;
            }
        }
        return bestF;
    }

    private double simpleFuzzy(String q, FieldInfo f) {
        String t = (f.getAliases() == null ? "" : f.getAliases())
                + " " + f.getDescription()
                + " " + f.getCanonicalField();
        t = t.toLowerCase();
        int hits = 0;
        for (char c : q.toCharArray())
            if (t.indexOf(c) >= 0) hits++;
        return hits / (double) Math.max(1, q.length());
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[_\\s]", "");
    }


    // ---------------- DTO ---------------------

    public static class Hit {
        public String inputName;
        public String inputDescription;
        public FieldInfo best;
        public List<Candidate> candidates;

        public static Hit fromCandidates(String name, String desc, List<Candidate> cs) {
            Candidate best = cs.stream()
                    .max(Comparator.comparingDouble(c -> c.score))
                    .orElse(new Candidate(null, 0, "none"));
            Hit h = new Hit();
            h.inputName = name;
            h.inputDescription = desc;
            h.best = best.field;
            h.candidates = cs;
            return h;
        }
    }

    public static class Candidate {
        public FieldInfo field;
        public double score;
        public String from;
        public Candidate(FieldInfo f, double s, String from) {
            this.field = f;
            this.score = s;
            this.from = from;
        }
    }

    private static class Scored {
        FieldInfo field;
        double score;
        Scored(FieldInfo f, double s) { this.field = f; this.score = s; }
    }
}
