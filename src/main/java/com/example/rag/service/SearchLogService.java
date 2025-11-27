package com.example.rag.service;

import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.time.LocalDateTime;

@Service
public class SearchLogService {

    private static final String LOG_FILE = "search_log.txt";

    public void log(String query, String detail) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write("[" + LocalDateTime.now() + "] query=" + query + "\n");
            fw.write(detail + "\n\n");
        } catch (Exception ignored) {}
    }
}
