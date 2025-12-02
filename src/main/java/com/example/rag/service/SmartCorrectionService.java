package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.example.rag.util.VectorUtils;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class SmartCorrectionService {

    private final KbService kb;
    private final EmbeddingService embeddingService;

    // alias -> canonical (from KB)
    private final Map<String, String> aliasIndex = new HashMap<>();
    // alias embedding cache
    private final Map<String, float[]> aliasVecCache = new HashMap<>();

    // manual correction map (from correction.csv) - only exact/manual overrides
    private final Map<String, String> manualMap = new HashMap<>();

    private final Pattern cleanPattern = Pattern.compile("(是什么|是啥|多少|一下|请问|呢|吗|的|一下|下)$");

    public SmartCorrectionService(KbService kb, EmbeddingService embeddingService) {
        this.kb = kb;
        this.embeddingService = embeddingService;
        // init moved to @PostConstruct
    }

    @PostConstruct
    public void init() {
        buildAliasIndex();
        loadManualCorrections(); // optional: loads correction.csv into manualMap
    }

    private void buildAliasIndex() {
        aliasIndex.clear();
        aliasVecCache.clear();
        for (FieldInfo f : kb.all()) {
            List<String> names = new ArrayList<>();
            if (f.getCanonicalField() != null) names.add(f.getCanonicalField());
            if (f.getColumnName() != null) names.add(f.getColumnName());
            if (f.getAliases() != null) names.addAll(Arrays.asList(f.getAliases().split("[,|;]")));

            for (String n : names) {
                if (n == null) continue;
                String key = normalize(n);
                if (!key.isEmpty()) {
                    // keep first mapping only to avoid override
                    aliasIndex.putIfAbsent(key, f.getCanonicalField());
                    float[] v = embedSafe(n);
                    if (v != null) aliasVecCache.putIfAbsent(key, v);
                }
            }
        }
    }

    private void loadManualCorrections() {
        // optional: read correction.csv at kb.correction-path or fixed path
        // format: query,canonical
        // If you already have CorrectionService that loads correction.csv, you can copy its map here.
        // For safety, keep manualMap empty if no file.
        // Example to load from "src/main/resources/data/correction.csv":
        String path = System.getProperty("kb.correction.path", "src/main/resources/data/correction.csv");
        File f = new File(path);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = parseCsvLine(line);
                if (parts.length >= 2) {
                    manualMap.put(normalize(parts[0]), parts[1].trim());
                }
            }
        } catch (Exception ignored) {}
    }

    /** EXPLICIT manual correction only: do NOT use embedding here */
    public String getManualCorrection(String text) {
        if (text == null) return null;
        return manualMap.get(normalize(clean(text)));
    }

    /**
     * Semantic suggestion: returns a Suggestion object (canonical + score) or null.
     * This is only a suggestion — caller must NOT treat it as authoritative correction.
     */
    public Suggestion suggestBySemantic(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String cleaned = clean(text);
        String key = normalize(cleaned);

        // 1) quick contains match against aliasIndex
        for (String a : aliasIndex.keySet()) {
            if (key.contains(a) || a.contains(key)) {
                String canonical = aliasIndex.get(a);
                if (canonical != null) return new Suggestion(canonical, 0.98, "contains");
            }
        }

        // 2) embedding semantic compare with aliasVecCache
        float[] q = embedSafe(cleaned);
        if (q == null) return null;
        q = VectorUtils.normalize(q);

        double best = 0;
        String bestCanonical = null;
        for (Map.Entry<String, float[]> e : aliasVecCache.entrySet()) {
            float[] v = e.getValue();
            if (v == null) continue;
            double s = VectorUtils.cosine(q, v);
            if (s > best) {
                best = s;
                bestCanonical = aliasIndex.get(e.getKey());
            }
        }

        // threshold — consider only reasonably confident suggestions
        if (best > 0.60 && bestCanonical != null) {
            double score = Math.min(0.95, best * 0.9); // scale down a bit
            return new Suggestion(bestCanonical, score, "semantic");
        }
        return null;
    }

    // safe embed that catches exceptions and returns normalized vector or null
    private float[] embedSafe(String t) {
        try {
            float[] vec = embeddingService.embed(t);
            if (vec == null) return null;
            return VectorUtils.normalize(vec);
        } catch (Exception e) {
            return null;
        }
    }

    private String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        return cleanPattern.matcher(s).replaceAll("");
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[_\\s]", "");
    }

    // minimal CSV parse for two columns (supports quoted)
    private String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++;
                } else inQuotes = !inQuotes;
                continue;
            }
            if (c == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else sb.append(c);
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    public static class Suggestion {
        public final String canonical;
        public final double score;
        public final String reason;
        public Suggestion(String canonical, double score, String reason) {
            this.canonical = canonical;
            this.score = score;
            this.reason = reason;
        }
    }
}
