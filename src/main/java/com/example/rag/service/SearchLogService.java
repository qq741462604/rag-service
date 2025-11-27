package com.example.rag.service;

import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

@Service
public class SearchLogService {

    private static final String LOG_FILE = "logs/search_log.txt";
    private final String LOG_PATH = "logs/search_log.csv";

    public SearchLogService() {
        // 确保文件有表头
        try {
            java.io.File f = new java.io.File(LOG_PATH);
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                try (FileWriter fw = new FileWriter(f, true)) {
                    fw.write("timestamp,query,result,score,method,correctionUsed\n");
                }
            }
        } catch (Exception ignore) {}
    }

    public void log(String query, String detail) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write("[" + LocalDateTime.now() + "] query=" + query + "\n");
            fw.write(detail + "\n\n");
        } catch (Exception ignored) {}

    }

    public void log(String query, String result, double score, String method, boolean corrected) {
        try (FileWriter fw = new FileWriter(LOG_PATH, true)) {
            fw.write(String.format("%s,%s,%s,%.4f,%s,%s\n",
                    LocalDateTime.now(),
                    safe(query),
                    safe(result),
                    score,
                    method,
                    corrected));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.replace(",", " ");
    }
}
