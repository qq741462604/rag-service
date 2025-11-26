package com.example.rag.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.rag.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AnswerClient {

    @Value("${qwen.api.key:}")
    private String qwenKey;

    @Value("${qwen.api.url:}")
    private String qwenUrl;

    @Value("${qwen.model:}")
    private String modelName;

    private final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /**
     * 简单封装：把 prompt 发给 qwen 模型并返回文本（注意：你需要按你们的 qwen endpoint 格式调整 body）
     */

    public String chat1(List<Map<String, Object>> messages) throws Exception {
        // 构建请求 JSON
        String jsonBody = String.format("{\"model\":\"%s\",\"messages\":%s}", modelName, JsonUtil.toJson(messages));

        RequestBody body = RequestBody.create( MediaType.parse("application/json; charset=utf-8"), jsonBody);
        Request request = new Request.Builder()
                .url(qwenUrl)
                .addHeader("Authorization", "Bearer " + qwenKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Qwen API 请求失败，HTTP " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("Qwen API 返回空响应");
            }
            String respStr = responseBody.string();
            log.debug("QwenClient resp: {}", respStr);
            return respStr;
        }
    }

    public String chat(String prompt) {
        if (qwenKey == null || qwenKey.isEmpty() || qwenUrl == null || qwenUrl.isEmpty()) {
            return "Qwen not configured";
        }
        try {
            JSONObject body = new JSONObject();
            body.put("model", modelName); // 仅示例，按实际服务调整
            body.put("messages", prompt);

            Request request = new Request.Builder()
                    .url(qwenUrl)
                    .addHeader("Authorization", "Bearer " + qwenKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), JSON_MEDIA))
                    .build();

            Response resp = client.newCall(request).execute();
            if (!resp.isSuccessful()) {
                String err = resp.body().string();
                resp.close();
                return "Qwen call failed: " + err;
            }
            String respBody = resp.body().string();
            resp.close();

            JSONObject root = JSONObject.parseObject(respBody);
            // 根据 qwen 返回调整解析路径，这里假设有 data[0].content 或 choices[0].text
            if (root.containsKey("data")) {
                JSONArray data = root.getJSONArray("data");
                if (data != null && data.size() > 0) {
                    return data.getJSONObject(0).getString("content");
                }
            }
            if (root.containsKey("choices")) {
                JSONArray choices = root.getJSONArray("choices");
                if (choices != null && choices.size() > 0) {
                    return choices.getJSONObject(0).getString("text");
                }
            }
            return respBody;
        } catch (Exception e) {
            e.printStackTrace();
            return "Qwen call exception: " + e.getMessage();
        }
    }
}
