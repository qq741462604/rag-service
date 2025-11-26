package com.example.rag.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.lang.Float;
@Component
public class EmbeddingClient {

    @Value("${llm.api.key:}")
    private String openaiKey;

    @Value("${llm.api.url:}")
    private String openaiUrl;

    @Value("${llm.model:qwen-plus}")
    private String modelName;
    private static final OkHttpClient client = new OkHttpClient();

    public float[] embed(String text) {

        try {
            JSONObject body = new JSONObject();
            body.put("model", modelName);
            body.put("input", text);

            Request request = new Request.Builder()
                    .url(openaiUrl)
                    .addHeader("Authorization", "Bearer " + openaiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), MediaType.parse("application/json")))
                    .build();

            Response response = client.newCall(request).execute();
            String json = response.body().string();

            JSONObject obj = JSON.parseObject(json);
            JSONObject data = obj.getJSONArray("data").getJSONObject(0);

            // embedding 是 float 数组
            JSONArray arr = data.getJSONArray("embedding");
            float[] result = new float[arr.size()];

            for (int i = 0; i < arr.size(); i++) {
                result[i] = ((Number) arr.get(i)).floatValue();
            }

            return result;

        } catch (Exception e) {
            e.printStackTrace();
        }

        // 失败返回 1536维零向量
        return new float[1536];
    }
}
