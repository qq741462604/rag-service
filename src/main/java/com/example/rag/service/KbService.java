package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.opencsv.CSVReader;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.FileReader;
import java.util.*;

@Service
public class KbService {

    private final Map<String, FieldInfo> fields = new LinkedHashMap<>();

    private final String KB_PATH = "src/main/resources/kb_with_embedding.csv";

    @PostConstruct
    public void load() throws Exception {
        CSVReader reader = new CSVReader(new FileReader(KB_PATH));
        String[] header = reader.readNext();
        String[] row;

        while ((row = reader.readNext()) != null) {

            FieldInfo f = new FieldInfo();
            f.canonical = row[0];
            f.column = row[1];
            f.aliases = row[2];
            f.description = row[3];
            f.embedding = parseEmbedding(row[4]);

            fields.put(f.canonical, f);
        }
    }

    public Collection<FieldInfo> all() {
        return fields.values();
    }

    private float[] parseEmbedding(String s) {
        String[] parts = s.split(",");
        float[] arr = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Float.parseFloat(parts[i]);
        }
        return arr;
    }
}
