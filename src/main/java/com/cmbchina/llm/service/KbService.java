package com.cmbchina.llm.service;

import com.cmbchina.llm.entity.model.FieldInfo;
import com.cmbchina.llm.util.CsvKeyCleaner;
import com.cmbchina.llm.util.ResourceFileUtils;
import com.opencsv.CSVReaderHeaderAware;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KbService - load kb_with_embedding.csv (with header names as in application.yml comment)
 */
@Service
@Slf4j
public class KbService {

    private final Map<String, FieldInfo> kb = new ConcurrentHashMap<>();
    private final Map<String, String> aliasToCanonical = new ConcurrentHashMap<>();

    private final ThreadPoolTaskExecutor executor;

    @Value("${kb.emb-path}")
    private String embPath;

    public KbService() {
        executor = new ThreadPoolTaskExecutor();
        // 初始化线程池
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("kb-loader-");
        executor.initialize();
    }

    @PostConstruct
    public void init() {
        loadKbAsync();
    }

    /**
     * 外部调用
     */
    public void reload() {
        loadKbAsync();
    }

    /**
     * 异步加载知识库
     */
    public void loadKbAsync() {
        executor.submit(this::loadEmbCsv);
    }

    public Collection<FieldInfo> all() {
        return kb.values();
    }

    public FieldInfo getByCanonical(String canonical) {
        return kb.get(canonical);
    }

    public String resolveAlias(String q) {
        if (q == null) {
            return null;
        }
        return aliasToCanonical.get(normalize(q));
    }

    private synchronized void loadEmbCsv() {
        log.info("Loading kb from {}", embPath);
        File f = ResourceFileUtils.getResourceAsFile(embPath);
        if (!f.exists()) {
            log.error("Failed to load kb_with_embedding.csv");
            return;
        }

        Map<String, FieldInfo> newKb = new ConcurrentHashMap<>();
        Map<String, String> newAlias = new ConcurrentHashMap<>();

        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
             CSVReaderHeaderAware reader = new CSVReaderHeaderAware(r)) {

            Map<String, String> rec;
            while ((rec = reader.readMap()) != null) {
                FieldInfo fi = new FieldInfo();
                rec = CsvKeyCleaner.cleanKeys(rec);

                String canonical = safeField(rec, "canonical_field", "canonicalField");
                String column = safeField(rec, "column_name", "columnName");

                fi.setCanonicalField(canonical);
                fi.setColumnName(column);
                fi.setDataType(safeField(rec, "data_type", "dataType"));
                fi.setLength(safeField(rec, "length"));
                fi.setDescription(safeField(rec, "description"));
                fi.setAliases(safeField(rec, "aliases"));
                fi.setRemark(safeField(rec, "remark"));

                try {
                    fi.setPriorityLevel(Integer.parseInt(safeField(rec, "priority_level", "priorityLevel")));
                } catch (Exception e) {
                    fi.setPriorityLevel(0);
                }

                String embS = safeField(rec, "embedding");
                if (embS != null && !embS.trim().isEmpty()) {
                    String[] parts = embS.split(",");
                    float[] vec = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        vec[i] = Float.parseFloat(parts[i]);
                    }
                    fi.setEmbedding(vec);
                }
                newKb.put(fi.getCanonicalField(), fi);

                if (fi.getAliases() != null && !fi.getAliases().isEmpty()) {
                    String[] as = fi.getAliases().split("[,|;]");
                    for (String a : as) {
                        String key = normalize(a);
                        if (!key.isEmpty()) {
                            newAlias.putIfAbsent(key, fi.getCanonicalField());
                        }
                    }
                }

                newAlias.putIfAbsent(normalize(fi.getCanonicalField()), fi.getCanonicalField());
                newAlias.putIfAbsent(normalize(fi.getColumnName()), fi.getCanonicalField());
            }
        } catch (Exception e) {
            log.error("kb.csv not found: {}", embPath);
        }

        kb.clear();
        kb.putAll(newKb);

        aliasToCanonical.clear();
        aliasToCanonical.putAll(newAlias);
        log.info("Loaded kb end with {} entries", kb.size());
    }
    private String safeField(Map<String, String> rec, String... names) {
        for (String n : names) {
            try {
                if (rec.containsKey(n)) {
                    String v = rec.get(n);
                    if (v != null) {
                        return v;
                    }
                }
            } catch (Exception ignored) {
                log.error("safeField error: {}", n);
            }
        }
        return "";
    }

    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase().replaceAll("[_\\s]", "");
    }
}
