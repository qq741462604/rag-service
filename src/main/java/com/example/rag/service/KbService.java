package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.opencsv.CSVReader;

import javax.annotation.PostConstruct;
import java.io.FileReader;
import java.util.*;

/**
 * 加载带 embedding 的 CSV 到内存
 */
@Service
public class KbService {

    private final Map<String, FieldInfo> canonicalToInfo = new LinkedHashMap<>();
    private final Map<String, String> aliasIndex = new HashMap<>();

    @Value("${kb.load.path}")
    private String kbLoadPath;

    @PostConstruct
    public void init() throws Exception {
        load(kbLoadPath);
    }

    public synchronized void load(String path) throws Exception {
        canonicalToInfo.clear();
        aliasIndex.clear();

        CSVReader r = null;
        try {
            r = new CSVReader(new FileReader(path));
            String[] header = r.readNext();
            if (header == null) return;
            String[] line;
            while ((line = r.readNext()) != null) {
                FieldInfo f = new FieldInfo();
                f.canonicalField = safe(line, 0);
                f.columnName = safe(line, 1);
                f.dataType = safe(line, 2);
                f.length = safe(line, 3);
                f.description = safe(line, 4);
                f.aliases = safe(line, 5);
                f.remark = safe(line, 6);
                f.priorityLevel = parseIntSafe(safe(line, 7));
                String emb = safe(line, 8);
                if (emb != null && !emb.isEmpty()) {
                    String[] parts = emb.split(",");
                    float[] vec = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        vec[i] = Float.parseFloat(parts[i]);
                    }
                    f.embedding = vec;
                }
                canonicalToInfo.put(f.canonicalField, f);

                // build alias index
                if (f.aliases != null && !f.aliases.isEmpty()) {
                    String[] arr = f.aliases.split(",");
                    for (String a : arr) {
                        String norm = normalize(a);
                        if (!norm.isEmpty() && !aliasIndex.containsKey(norm)) {
                            aliasIndex.put(norm, f.canonicalField);
                        }
                    }
                }
                aliasIndex.putIfAbsent(normalize(f.canonicalField), f.canonicalField);
                aliasIndex.putIfAbsent(normalize(f.columnName), f.canonicalField);
            }
        } finally {
            if (r != null) try { r.close(); } catch (Exception ex) {}
        }
    }

    private String safe(String[] arr, int idx) {
        if (arr == null || idx >= arr.length) return "";
        return arr[idx] == null ? "" : arr[idx].trim();
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[_\\s]", "");
    }

    public Optional<String> lookupAlias(String q) {
        return Optional.ofNullable(aliasIndex.get(normalize(q)));
    }

    public Collection<FieldInfo> all() { return canonicalToInfo.values(); }

    public FieldInfo get(String canonical) { return canonicalToInfo.get(canonical); }
}
