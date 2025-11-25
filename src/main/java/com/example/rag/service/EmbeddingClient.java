package com.example.rag.service;


import com.alibaba.dashscope.aigc.generation.TextEmbeddingClient;
import com.alibaba.dashscope.aigc.generation.TextEmbeddingRequest;
import com.alibaba.dashscope.aigc.generation.TextEmbeddingResponse;

@Component
public class EmbeddingClient {

    @Value("${llm.api.key:}")
    private String openaiKey;

    @Value("${llm.api.url:}")
    private String openaiUrl;

    @Value("${llm.model:qwen-plus}")
    private String modelName;

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public float[] embed(String text) {
        try {
            // 创建请求对象
            TextEmbeddingRequest request = new TextEmbeddingRequest();
            request.setApiKey(API_KEY);  // 你的 API Key
            request.setInput(text);      // 要 embedding 的文本
            request.setModel("text-embedding-v2");  // 模型名称

            // 调用 DashScope API 获取 embedding
            TextEmbeddingResponse response = TextEmbeddingClient.textEmbedding(request);

            // 确保返回数据存在
            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                // 返回第一条记录的 embedding
                return response.getData().get(0).getEmbedding();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 如果 API 调用失败，返回一个默认的零向量
        return new float[768];  // 假设 embedding 维度为 768
    }
}

    public float[] embed1(String text) {
        if (openaiKey == null || openaiKey.isEmpty()) return null; // disabled

        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", modelName);
            payload.put("input", text);

            RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(openaiUrl)
                    .addHeader("Authorization", "Bearer " + openaiKey)
                    .post(body)
                    .build();

            Response resp = client.newCall(request).execute();
            if (!resp.isSuccessful()) return null;
            String respBody = resp.body().string();
            JsonNode root = mapper.readTree(respBody);
            JsonNode arr = root.path("data").get(0).path("embedding");
            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) vec[i] = (float) arr.get(i).asDouble();
            return vec;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static double cosSim(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) return -1;
        double dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            n1 += v1[i] * v1[i];
            n2 += v2[i] * v2[i];
        }
        if (n1 == 0 || n2 == 0) return -1;
        return dot / (Math.sqrt(n1) * Math.sqrt(n2));
    }
}
