package com.example.rag.service;

import com.example.rag.embedding.EmbeddingService;
import com.example.rag.model.FieldInfo;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson.JSON;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;

@Service
public class KbLoader {

    private final String RAW_CSV = "src/main/resources/data/kb.csv";
    private final String EMB_CSV = "src/main/resources/data/kb_with_embedding.csv";

    private final Map<String, FieldInfo> kb = new HashMap<>();
    private final EmbeddingService embeddingService;

    public KbLoader(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() throws Exception {
        File f = new File(EMB_CSV);

        if (!f.exists()) {
            System.out.println(">>> kb_with_embedding.csv 不存在，开始生成 embedding ...");
            generateEmbCsv();
        }

//        loadEmbCsv();
    }

    /** 加载最终的 kb_with_embedding.csv */
    private void loadEmbCsv() throws Exception {
        try (CSVReader reader = new CSVReader(new FileReader(EMB_CSV))) {
            reader.readNext(); // header

            String[] row;
            while ((row = reader.readNext()) != null) {
                FieldInfo f = new FieldInfo();
                f.canonicalField = row[0];
                f.columnName = row[1];
                f.dataType = row[2];
                f.length = row[3];
                f.description = row[4];
                f.aliases = row[5];
                f.remark = row[6];
                f.priorityLevel = Integer.parseInt(row[7]);
                f.embedding = JSON.parseObject(row[8], float[].class);

                kb.put(f.canonicalField, f);
            }
        }
        System.out.println(">>> 已加载 KB（含向量）共 " + kb.size() + " 条记录");
    }

    /** 读取原始 kb.csv，生成 kb_with_embedding.csv */
    private void generateEmbCsv() throws Exception {

        List<FieldInfo> list = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new FileReader(RAW_CSV))) {
            reader.readNext(); // header

            String[] row;
            while ((row = reader.readNext()) != null) {

                FieldInfo f = new FieldInfo();
                f.canonicalField = row[0];
                f.columnName = row[1];
                f.dataType = row[2];
                f.length = row[3];
                f.description = row[4];
                f.aliases = row[5];
                f.remark = row[6];
                f.priorityLevel = Integer.parseInt(row[7]);

                String embInput = buildEmbeddingInput(f);
                f.embedding = embeddingService.embed(embInput);

                list.add(f);
            }
        }

        saveEmbCsv(list);
    }

    /** 更新 kb_with_embedding.csv */
    private void saveEmbCsv(List<FieldInfo> list) throws Exception {
        try (CSVWriter writer = new CSVWriter(new FileWriter(EMB_CSV))) {
            writer.writeNext(new String[]{
                    "canonicalField","columnName","dataType","length","description",
                    "aliases","remark","priorityLevel","embedding"
            });

            for (FieldInfo f : list) {
                writer.writeNext(new String[]{
                        f.canonicalField,
                        f.columnName,
                        f.dataType,
                        f.length,
                        f.description,
                        f.aliases,
                        f.remark,
                        String.valueOf(f.priorityLevel),
                        JSON.toJSONString(f.embedding)
                });
            }
        }
        System.out.println(">>> 已生成 kb_with_embedding.csv");
    }

    /** 为 embedding 构建语义输入文本 */
    private String buildEmbeddingInput(FieldInfo f) {
        return String.join("\n", Arrays.asList(
                "字段名:" + f.canonicalField,
                "列名:" + f.columnName,
                "类型:" + f.dataType,
                "长度:" + f.length,
                "描述:" + f.description,
                "别名:" + f.aliases,
                "备注:" + f.remark
        ));
    }

    public Collection<FieldInfo> all() {
        return kb.values();
    }

    public FieldInfo getByCanonical(String key) {
        return kb.get(key);
    }
}
