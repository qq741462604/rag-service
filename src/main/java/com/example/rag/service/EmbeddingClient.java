package com.example.rag.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingClient {

    @Value("${embedding.api.key}")
    private String apiKey;

    @Value("${embedding.api.url}")
    private String apiUrl;

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();

    /**
     * 调用 embedding API，返回 float[]（若失败返回 null）
     */
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isEmpty()) return null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", "text-embedding-v2"); // 或按你实际使用的模型名
            body.put("input", text);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), JSON_MEDIA))
                    .build();

            Response resp = client.newCall(request).execute();
            if (!resp.isSuccessful()) {
                resp.close();
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
