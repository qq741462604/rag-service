package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.opencsv.CSVReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.FileReader;
import java.util.*;

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

    public void load(String path) throws Exception {
        canonicalToInfo.clear();
        aliasIndex.clear();
        try (CSVReader r = new CSVReader(new FileReader(path))) {
            String[] header = r.readNext();
            if (header == null) return;
            String[] line;
            while ((line = r.readNext()) != null) {
                // expect columns: canonicalField,columnName,dataType,length,description,aliases,remark,priorityLevel,embedding
                FieldInfo f = new FieldInfo();
                f.canonicalField = safe(line, 0);
                f.columnName = safe(line, 1);
                f.dataType = safe(line, 2);
                f.length = safe(line, 3);
                f.description = safe(line, 4);
                f.aliases = safe(line, 5);
                f.remark = safe(line, 6);
                f.priorityLevel = parseIntSafe(safe(line, 7));
                String embStr = safe(line, 8);
                if (embStr != null && !embStr.isEmpty()) {
                    String[] parts = embStr.split(",");
                    float[] vec = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        vec[i] = Float.parseFloat(parts[i]);
                    }
                    f.embedding = vec;
                }
                canonicalToInfo.put(f.canonicalField, f);

                // alias index
                if (f.aliases != null && !f.aliases.isEmpty()) {
                    String[] arr = f.aliases.split(",");
                    for (String a : arr) {
                        String norm = normalize(a);
                        if (!norm.isEmpty() && !aliasIndex.containsKey(norm)) {
                            aliasIndex.put(norm, f.canonicalField);
                        }
                    }
                }
                // also index canonical and columnName
                aliasIndex.putIfAbsent(normalize(f.canonicalField), f.canonicalField);
                aliasIndex.putIfAbsent(normalize(f.columnName), f.canonicalField);
            }
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
