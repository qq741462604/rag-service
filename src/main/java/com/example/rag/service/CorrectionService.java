package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CorrectionService - 纠错学习模块
 *
 * correction.csv 格式：query,canonicalField
 * 一行一条（CSV，query 可能带逗号时请用引号）
 */
@Service
public class CorrectionService {

    private static final String FILE = "src/main/resources/data/correction.csv";

    private final Map<String, CorrectionItem> map = new HashMap<>();

    @Autowired
    private KbService kbService;

    @PostConstruct
    public void init() {
        File f = new File(FILE);
        if (!f.exists()) {
            // ensure dir
            f.getParentFile().mkdirs();
            try {
                f.createNewFile();
            } catch (IOException ignored) {}
        }
        load();
    }
    public static class CorrectionItem {
        public String query;
        public String wrongCanonical;
        public String correctCanonical;
    }
    private void load() {
        try {
            if (!Files.exists(Paths.get(FILE))) return;
            List<String> lines = Files.readAllLines(Paths.get(FILE));
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] arr = line.split(",");
                if (arr.length < 3) continue;
                CorrectionItem c = new CorrectionItem();
                c.query = arr[0];
                c.wrongCanonical = arr[1];
                c.correctCanonical = arr[2];
                map.put(c.query, c);
            }
        } catch (Exception ignored) {}
    }

    public boolean record(String query, String wrong, String correct) {
        CorrectionItem c = new CorrectionItem();
        c.query = query;
        c.wrongCanonical = wrong;
        c.correctCanonical = correct;
        map.put(query, c);
        return persist();
    }


    /**
     * 写入
     * @return
     */
    private boolean persist() {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(FILE))) {
            for (CorrectionItem c : map.values()) {
                w.write(c.query + "," + c.wrongCanonical + "," + c.correctCanonical + "\n");
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalize(String s) {
        return s == null ? null : s.trim().toLowerCase();
    }

    public CorrectionItem findByQuery(String query) {
        return map.get(query);
    }

    /**
     * 返回修正后的 canonical（如果存在）
     */
    public String applyCorrection(String query) {
        if (query == null) return null;
        CorrectionItem c = map.get(query);
        if (c == null) return null;
        return c.correctCanonical;
    }

    /**
     * 纠错覆盖逻辑
     * query → canonical → FieldInfo
     */
    public FieldInfo checkCorrection(String query) {
        CorrectionItem c = map.get(query.trim().toLowerCase());
        if (c == null) return null;
        String canonical = c.correctCanonical;

        if (canonical == null) return null;

        // 关键点：从 KB 中取完整 FieldInfo
        return kbService.getByCanonical(canonical);
    }
}
