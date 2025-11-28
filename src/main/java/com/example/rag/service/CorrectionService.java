package com.example.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CorrectionService: stores query->canonical mapping (two columns CSV)
 */
@Service
public class CorrectionService {

    @Value("${kb.correction-path}")
    private String correctionPath;

    private final Map<String, String> map = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        File f = new File(correctionPath);
        try {
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                f.createNewFile();
            }
        } catch (Exception ignored) {}
        load();
    }

    private void load() {
        File f = new File(correctionPath);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] arr = parseCsvLine(line);
                if (arr.length >= 2) {
                    map.put(normalize(arr[0]), arr[1].trim());
                }
            }
        } catch (Exception ignored) {}
    }

    public String getCorrection(String query) {
        if (query == null) return null;
        return map.get(normalize(query));
    }

    public synchronized boolean recordCorrection(String query, String canonical) {
        if (query == null || canonical == null) return false;
        String line = csvEscape(query) + "," + csvEscape(canonical) + "\n";
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(correctionPath, true), StandardCharsets.UTF_8))) {
            bw.write(line);
            bw.flush();
            map.put(normalize(query), canonical);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // helpers: simple CSV parse/escape
    private String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String[] parseCsvLine(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<line.length();i++){
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i+1 < line.length() && line.charAt(i+1) == '"') { sb.append('"'); i++; }
                else { inQuotes = !inQuotes; }
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

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    // convenience for EnhancedVectorSearchService in previous messages
    public String lookupCorrect(String q) {
        return getCorrection(q);
    }
}
