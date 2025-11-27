package com.example.rag.service;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
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

    private static final String CORRECTION_FILE = "src/main/resources/data/correction.csv";
    private final Map<String, String> corrections = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        File f = new File(CORRECTION_FILE);
        if (!f.exists()) {
            // ensure dir
            f.getParentFile().mkdirs();
            try {
                f.createNewFile();
            } catch (IOException ignored) {}
        }
        load();
    }

    private void load() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(CORRECTION_FILE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] arr = parseCsvLine(line);
                if (arr.length >= 2) {
                    corrections.put(normalize(arr[0]), arr[1]);
                }
            }
        } catch (Exception e) {
            // ignore, log if needed
            e.printStackTrace();
        }
    }

    /**
     * 将纠错保存到文件并更新内存
     */
    public synchronized boolean recordCorrection(String query, String canonical) {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(CORRECTION_FILE, true), StandardCharsets.UTF_8))) {
            String line = csvEscape(query) + "," + csvEscape(canonical) + "\n";
            bw.write(line);
            bw.flush();
            corrections.put(normalize(query), canonical);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 返回修正后的 canonical（如果存在）
     */
    public String applyCorrection(String query) {
        if (query == null) return null;
        return corrections.get(normalize(query));
    }

    private String normalize(String s) {
        return s == null ? null : s.trim().toLowerCase();
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // 简单的 CSV 解析（支持双引号）
    private String[] parseCsvLine(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); // escaped quote
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (c == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }
}
