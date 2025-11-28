package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KbService - load kb_with_embedding.csv (with header names as in application.yml comment)
 */
@Service
public class KbService {

    private final Map<String, FieldInfo> kb = new ConcurrentHashMap<>();
    private final Map<String, String> aliasToCanonical = new ConcurrentHashMap<>();

    @Value("${kb.emb-path}")
    private String embPath;

    @PostConstruct
    public void init() {
        // do not fail app start if file missing; user can call reload after generation
        try {
            File f = new File(embPath);
            if (f.exists()) loadEmbCsv();
        } catch (Exception e) {
            // log but don't throw to allow app to start
            e.printStackTrace();
        }
    }

    public synchronized void reload() throws Exception {
        loadEmbCsv();
    }

    public Collection<FieldInfo> all() {
        return kb.values();
    }

    public FieldInfo getByCanonical(String canonical) {
        return kb.get(canonical);
    }

    public String resolveAlias(String q) {
        if (q == null) return null;
        return aliasToCanonical.get(normalize(q));
    }

    private void loadEmbCsv() throws Exception {
        File f = new File(embPath);
        if (!f.exists()) throw new FileNotFoundException("kb_with_embedding.csv not found: " + embPath);

        Map<String, FieldInfo> newKb = new ConcurrentHashMap<>();
        Map<String, String> newAlias = new ConcurrentHashMap<>();

        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(r)) {

            for (CSVRecord rec : parser) {
                FieldInfo fi = new FieldInfo();
                // Use header names - tolerant to underscores vs camel
                String canonical = safeField(rec, "canonical_field", "canonicalField");
                String column = safeField(rec, "column_name", "columnName");
                fi.setCanonicalField(canonical);
                fi.setColumnName(column);
                fi.setDataType(safeField(rec, "data_type", "dataType"));
                fi.setLength(safeField(rec, "length"));
                fi.setDescription(safeField(rec, "description"));
                fi.setAliases(safeField(rec, "aliases"));
                fi.setRemark(safeField(rec, "remark"));
                try { fi.setPriorityLevel(Integer.parseInt(safeField(rec, "priority_level", "priorityLevel"))); } catch (Exception e) { fi.setPriorityLevel(0); }

                String embS = safeField(rec, "embedding");
                if (embS != null && !embS.trim().isEmpty()) {
                    String[] parts = embS.split(",");
                    float[] vec = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) vec[i] = Float.parseFloat(parts[i]);
                    fi.setEmbedding(vec);
                }

                newKb.put(fi.getCanonicalField(), fi);

                if (fi.getAliases() != null && !fi.getAliases().isEmpty()) {
                    String[] as = fi.getAliases().split("[,|;]");
                    for (String a : as) {
                        String key = normalize(a);
                        if (!key.isEmpty()) newAlias.putIfAbsent(key, fi.getCanonicalField());
                    }
                }

                newAlias.putIfAbsent(normalize(fi.getCanonicalField()), fi.getCanonicalField());
                newAlias.putIfAbsent(normalize(fi.getColumnName()), fi.getCanonicalField());
            }
        }

        kb.clear(); kb.putAll(newKb);
        aliasToCanonical.clear(); aliasToCanonical.putAll(newAlias);
    }

    private String safeField(CSVRecord rec, String... names) {
        for (String n : names) {
            try {
                if (rec.isMapped(n)) {
                    String v = rec.get(n);
                    if (v != null) return v;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[_\\s]", "");
    }
}
