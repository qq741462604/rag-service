package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.opencsv.CSVReader;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.FileReader;
import java.util.*;

@Service
public class KbService {

    private final Map<String, String> aliasToCanonical = new HashMap<>();
    private final Map<String, FieldInfo> canonicalToInfo = new HashMap<>();

    private final String kbPath = System.getProperty("kb.path",
            "src/main/resources/data/kb.csv");

    @PostConstruct
    public void init() throws Exception {
        loadKb(kbPath);
    }

    private void loadKb(String path) throws Exception {
        try (CSVReader r = new CSVReader(new FileReader(path))) {
            String[] header = r.readNext(); // skip header
            String[] line;

            while ((line = r.readNext()) != null) {

                FieldInfo f = new FieldInfo();
                f.canonicalField = line[0].trim();
                f.columnName = line[1].trim();
                f.dataType = line[2].trim();
                f.length = line[3].trim();
                f.description = line[4].trim();
                f.aliases = line[5].trim();
                f.remark = line.length > 6 ? line[6].trim() : "";
                f.priorityLevel = line.length > 7 && !line[7].isEmpty()
                        ? Integer.parseInt(line[7]) : 0;

                canonicalToInfo.put(f.canonicalField, f);

                // 别名建立映射
                for (String a : f.aliases.split(",")) {
                    aliasToCanonical.putIfAbsent(normalize(a), f.canonicalField);
                }

                // canonical 字段本身
                aliasToCanonical.putIfAbsent(normalize(f.canonicalField), f.canonicalField);
                aliasToCanonical.putIfAbsent(normalize(f.columnName), f.canonicalField);
            }
        }
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase()
                .replaceAll("[_\\s]", "");
    }

    public Optional<String> lookup(String q) {
        return Optional.ofNullable(aliasToCanonical.get(normalize(q)));
    }

    public FieldInfo getInfo(String canonical) {
        return canonicalToInfo.get(canonical);
    }

    public Collection<FieldInfo> all() {
        return canonicalToInfo.values();
    }
}
