package com.example.rag.service;

import com.alibaba.fastjson.JSONArray;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import java.io.*;
import java.util.Arrays;

public class GenerateEmbedding {

    // NOTE: 此类不是 Spring Bean —— 作为独立工具运行（main）
    public static void main(String[] args) throws Exception {
        String inputPath = System.getProperty("kb.input.path", "src/main/resources/data/kb.csv");
        String outputPath = System.getProperty("kb.output.path", "src/main/resources/data/kb_with_embedding.csv");
        String apiKey = System.getProperty("embedding.api.key");

        EmbeddingClient client = new EmbeddingClient();
        // set apiKey via system property or environment
        // if you want to set apiKey programmatically, add a setter in EmbeddingClient

        try (CSVReader reader = new CSVReader(new InputStreamReader(new FileInputStream(inputPath), "UTF-8"));
             CSVWriter writer = new CSVWriter(new OutputStreamWriter(new FileOutputStream(outputPath), "UTF-8"))) {

            String[] header = reader.readNext();
            if (header == null) {
                System.err.println("input CSV empty");
                return;
            }

            // 新表头：在末尾添加 embedding 列
            String[] newHeader = Arrays.copyOf(header, header.length + 1);
            newHeader[newHeader.length - 1] = "embedding";
            writer.writeNext(newHeader);

            String[] row;
            int rowIdx = 0;
            while ((row = reader.readNext()) != null) {
                rowIdx++;
                // 如果原 CSV 已经有 embedding 列（防护），检查并跳过
                if (row.length >= newHeader.length && row[newHeader.length - 1] != null && !row[newHeader.length - 1].isEmpty()) {
                    writer.writeNext(row);
                    continue;
                }

                // 拼接文本：你可以自定义拼接哪些字段更好
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < header.length; i++) {
                    if (row.length > i && row[i] != null) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(row[i]);
                    }
                }
                String text = sb.toString();

                // 调用 embedding（注意：EmbeddingClient 从 Spring 注入的 config 读取不到，这里直接用 env）
                // 为简单演示，我们创建一个 EmbeddingClient 并要求 embedding.api.key 用 env 或系统属性提供
                String key = apiKey;
                if (key == null || key.isEmpty()) {
                    key = System.getenv("EMBEDDING_API_KEY");
                }
                // set key into client via reflection hack or re-create client with setter
                // simpler: temporarily set system property in EmbeddingClient uses @Value only in Spring;
                // So we will call external simple HTTP here instead of EmbeddingClient to avoid Spring wiring.
                float[] vec = callEmbeddingHttp(text, key);
                String embStr = "";
                if (vec != null) {
                    StringBuilder sbv = new StringBuilder();
                    for (int i = 0; i < vec.length; i++) {
                        if (i > 0) sbv.append(",");
                        sbv.append(vec[i]);
                    }
                    embStr = sbv.toString();
                }

                // 写入新行（append embedding 列）
                String[] newRow = Arrays.copyOf(row, newHeader.length);
                newRow[newRow.length - 1] = embStr;
                writer.writeNext(newRow);

                if (rowIdx % 50 == 0) System.out.println("Processed rows: " + rowIdx);
            }

            System.out.println("Embedding generation finished. Output: " + outputPath);
        }
    }

    // 轻量内嵌 HTTP 调用，避免 Spring 注入问题（复用 EmbeddingClient 的 URL 结构）
    private static float[] callEmbeddingHttp(String text, String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("embedding.api.key not provided");
            return null;
        }
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json; charset=utf-8");
        try {
            com.alibaba.fastjson.JSONObject body = new com.alibaba.fastjson.JSONObject();
            body.put("model", "text-embedding-v2");
            body.put("input", text);

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(System.getProperty("embedding.api.url", "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create(body.toJSONString(), JSON))
                    .build();

            okhttp3.Response resp = client.newCall(request).execute();
            if (!resp.isSuccessful()) {
                System.err.println("Embedding API failed: " + resp.code());
                resp.close();
                return null;
            }
            String respBody = resp.body().string();
            resp.close();

            com.alibaba.fastjson.JSONObject root = com.alibaba.fastjson.JSON.parseObject(respBody);
            com.alibaba.fastjson.JSONArray data = root.getJSONArray("data");
            if (data == null || data.size() == 0) return null;
            com.alibaba.fastjson.JSONArray emb = data.getJSONObject(0).getJSONArray("embedding");
            if (emb == null) return null;
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                Number n = (Number) emb.get(i);
                vec[i] = n.floatValue();
            }
            return vec;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
