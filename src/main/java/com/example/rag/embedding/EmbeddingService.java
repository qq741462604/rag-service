package com.example.rag.embedding;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * EmbeddingService - 通过 HTTP 调用 DashScope / Qwen embedding 接口
 *
 * 使用：
 *   在 application.properties 配置 embedding.api.key 与 embedding.api.url
 *
 * 特性：
 *   - 单条 embed (embed)
 *   - 批量 embed (batchEmbed) 会自动分批调用
 *   - 支持简单重试与指数退避
 */
@Service
public class EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${embedding.api.key}")
    private String apiKey;

    @Value("${embedding.api.url}")
    private String apiUrl;

    // 每次批量请求的最大条数（根据服务限流调整，默认为 16）
    private static final int BATCH_SIZE = 16;

    // 重试次数
    private static final int MAX_RETRIES = 3;

    // HTTP client（设定较短超时）
    private final OkHttpClient client;

    public EmbeddingService() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 单条文本生成 embedding（返回 float[]，失败返回 null）
     */
    public float[] embed(String text) {
        if (text == null) return null;
        List<float[]> list = batchEmbedSingle(text);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /**
     * 批量生成 embedding，返回和输入 texts 顺序一一对应的 float[] 列表
     * 失败项为 null
     */
    public List<float[]> batchEmbed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return new ArrayList<float[]>();

        List<float[]> results = new ArrayList<float[]>();
        int start = 0;
        while (start < texts.size()) {
            int end = Math.min(start + BATCH_SIZE, texts.size());
            List<String> sub = texts.subList(start, end);

            List<float[]> part = callEmbeddingWithRetries(sub);
            if (part == null) {
                // 如果一次批次全失败，填充 null 保持顺序
                for (int i = 0; i < sub.size(); i++) results.add(null);
            } else {
                results.addAll(part);
            }

            start = end;

            // 可选：短暂停顿，避免短时间内突发请求导致被限流（根据需要调整/移除）
            try { Thread.sleep(100L); } catch (InterruptedException ignored) {}
        }
        return results;
    }

    /* ---------- internal helpers ---------- */

    // 将单条文本包装为批量处理以复用批量逻辑
    private List<float[]> batchEmbedSingle(String text) {
        List<String> list = new ArrayList<String>(1);
        list.add(text);
        return callEmbeddingWithRetries(list);
    }

    // 带重试（指数退避）
    private List<float[]> callEmbeddingWithRetries(List<String> inputs) {
        int attempt = 0;
        long backoff = 500L; // ms
        while (attempt < MAX_RETRIES) {
            try {
                List<float[]> r = callEmbeddingApi(inputs);
                return r;
            } catch (IOException e) {
                attempt++;
                logger.warn("Embedding API call failed attempt={} error={}", attempt, e.getMessage());
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                backoff *= 2;
            }
        }
        logger.error("Embedding API all retries failed for {} items", inputs.size());
        return null;
    }

    /**
     * 实际调用 embedding HTTP API 并解析结果
     * 返回和 inputs 一一对应的 float[] 列表（如果某条解析失败，对应位置为 null）
     */
    private List<float[]> callEmbeddingApi(List<String> inputs) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.error("embedding.api.key is not set");
            return null;
        }
        if (apiUrl == null || apiUrl.isEmpty()) {
            logger.error("embedding.api.url is not set");
            return null;
        }

        JSONObject body = new JSONObject();
        // 根据你的服务要求调整字段：model / input / inputs 等
        // 这里兼容两种常见形式：单 input 或 inputs 列表；优先使用 "input" 为数组
        if (inputs.size() == 1) {
            body.put("input", inputs.get(0));
        } else {
            // Many providers expect "input": [ "...", "..." ]
            JSONArray arr = new JSONArray();
            for (String s : inputs) arr.add(s);
            body.put("input", arr);
        }
        // 指定模型名（如 text-embedding-v2），按你们内部约定调整
        body.put("model", "text-embedding-v2");

        RequestBody rb = RequestBody.create(body.toJSONString(), MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(rb)
                .build();

        Response resp = client.newCall(req).execute();
        if (!resp.isSuccessful()) {
            String err = resp.body() != null ? resp.body().string() : ("code=" + resp.code());
            resp.close();
            throw new IOException("Embedding API http error: " + err);
        }
        String respBody = resp.body().string();
        resp.close();

        // 解析返回 JSON（兼容 common provider response）
        // 预期结构： { "data": [ { "embedding": [0.1, 0.2, ...] }, ... ] }
        JSONObject root = JSONObject.parseObject(respBody);
        if (root == null) throw new IOException("Embedding API returned empty body");

        JSONArray data = root.getJSONArray("data");
        if (data == null || data.size() == 0) {
            // 有些服务把向量放到 "result"/"outputs" 中，尝试兼容性解析
            JSONArray alt = root.getJSONArray("outputs");
            if (alt != null && alt.size() > 0) data = alt;
        }
        if (data == null || data.size() == 0) {
            throw new IOException("Embedding API no data field: " + respBody);
        }

        // parse embeddings, 保证返回数量跟 inputs 对应（若 provider 行为不同，尽量尝试映射）
        List<float[]> out = new ArrayList<float[]>();
        for (int i = 0; i < data.size(); i++) {
            try {
                JSONObject item = data.getJSONObject(i);
                // 支持多种命名：embedding / embeddings / vector
                JSONArray emb = item.getJSONArray("embedding");
                if (emb == null) emb = item.getJSONArray("embeddings");
                if (emb == null) emb = item.getJSONArray("vector");
                if (emb == null) {
                    // 有的 provider 会把 embedding 放在 item.getJSONObject("result").getJSONArray("embedding")
                    JSONObject possible = item.getJSONObject("result");
                    if (possible != null) emb = possible.getJSONArray("embedding");
                }
                if (emb == null) {
                    logger.warn("No embedding field for item {} resp snippet {}", i, item.toJSONString().substring(0, Math.min(200, item.toJSONString().length())));
                    out.add(null);
                    continue;
                }
                float[] vec = new float[emb.size()];
                for (int j = 0; j < emb.size(); j++) {
                    Number n = (Number) emb.get(j);
                    vec[j] = (n == null) ? 0f : n.floatValue();
                }
                out.add(vec);
            } catch (Exception e) {
                logger.warn("Failed to parse embedding item {}: {}", i, e.getMessage());
                out.add(null);
            }
        }

        // 如果 provider 返回的 data 数量少于 inputs（奇怪情况），补齐 null 保持顺序
        while (out.size() < inputs.size()) out.add(null);
        return out;
    }
}
