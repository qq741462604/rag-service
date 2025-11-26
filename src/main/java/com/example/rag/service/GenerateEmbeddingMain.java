package com.example.rag.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import java.io.*;
import java.util.Arrays;

public class GenerateEmbeddingMain {

    // 作为独立工具运行 main
    public static void main(String[] args) throws Exception {
        String inputPath = System.getProperty("kb.input.path", "src/main/resources/data/kb.csv");
        String outputPath = System.getProperty("kb.output.path", "src/main/resources/data/kb_with_embedding.csv");
        String apiKey = System.getProperty("embedding.api.key");
        String apiUrl = System.getProperty("embedding.api.url",
                "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/generation");

        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("EMBEDDING_API_KEY");
        }

        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please provide embedding.api.key via -Dembedding.api.key or EMBEDDING_API_KEY env");
            return;
        }

        EmbeddingHttpHelper helper = new EmbeddingHttpHelper(apiKey, apiUrl);

        try (CSVReader reader = new CSVReader(new InputStreamReader(new FileInputStream(inputPath), "UTF-8"));
             CSVWriter writer = new CSVWriter(new OutputStreamWriter(new FileOutputStream(outputPath), "UTF-8"))) {

            String[] header = reader.readNext();
            if (header == null) {
                System.err.println("input CSV empty");
                return;
            }

            // 增加 embedding 列
            String[] newHeader = Arrays.copyOf(header, header.length + 1);
            newHeader[newHeader.length - 1] = "embedding";
            writer.writeNext(newHeader);

            String[] row;
            int idx = 0;
            while ((row = reader.readNext()) != null) {
                idx++;
                // 如果已经有 embedding（防重复）
                if (row.length >= newHeader.length && row[newHeader.length - 1] != null && !row[newHeader.length - 1].isEmpty()) {
                    writer.writeNext(row);
                    continue;
                }

                // 把可用字段拼成文本（可按需调整）
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < header.length; i++) {
                    if (i < row.length && row[i] != null && !row[i].trim().isEmpty()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(row[i].trim());
                    }
                }
                String text = sb.toString();

                float[] vec = helper.callEmbedding(text);
                String embStr = "";
                if (vec != null) {
                    StringBuilder sbv = new StringBuilder();
                    for (int i = 0; i < vec.length; i++) {
                        if (i > 0) sbv.append(",");
                        sbv.append(vec[i]);
                    }
                    embStr = sbv.toString();
                }

                String[] newRow = Arrays.copyOf(row, newHeader.length);
                newRow[newRow.length - 1] = embStr;
                writer.writeNext(newRow);

                if (idx % 50 == 0) System.out.println("Processed: " + idx);
            }
            System.out.println("Done, output: " + outputPath);
        }
    }

    // 内嵌轻量调用，避免依赖 Spring wiring（与 EmbeddingClient 功能重复，但便于命令行工具使用）
    static class EmbeddingHttpHelper {
        private final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        private final String apiKey;
        private final String apiUrl;

        EmbeddingHttpHelper(String apiKey, String apiUrl) {
            this.apiKey = apiKey;
            this.apiUrl = apiUrl;
        }

        public float[] callEmbedding(String text) {
            try {
                JSONObject body = new JSONObject();
                body.put("model", "text-embedding-v2");
                body.put("input", text);

                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(okhttp3.RequestBody.create(body.toJSONString(), okhttp3.MediaType.parse("application/json; charset=utf-8")))
                        .build();

                okhttp3.Response resp = client.newCall(request).execute();
                if (!resp.isSuccessful()) {
                    resp.close();
                    System.err.println("Embedding API fail code=" + resp.code());
                    return null;
                }
                String respBody = resp.body().string();
                resp.close();

                JSONObject root = JSONObject.parseObject(respBody);
                if (root == null) return null;
                JSONArray data = root.getJSONArray("data");
                if (data == null || data.size() == 0) return null;
                JSONArray emb = data.getJSONObject(0).getJSONArray("embedding");
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
}
