package com.example.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.rag.util.VectorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Service
@Slf4j
public class EmbeddingService {

    @Value("${dashscope.api.url}")
    private String apiUrl;

    @Value("${dashscope.api.key:}")
    private String apiKey;

    @Value("${model.name}")
    private String modelName;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Request embedding for a piece of text. Returns normalized float[] or null on error.
     */
    public float[] embed(String text) {
        try {
            if (apiUrl == null || apiUrl.isEmpty()) {
                log.error("dashscope.api.url not configured");
                return null;
            }

            // Build request body (adjust to DashScope API spec if needed)
            String body = mapper.writeValueAsString(new Payload(modelName, text));

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("utf-8"));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            JsonNode root = mapper.readTree(is);

            // try several common paths
            JsonNode embNode = null;
            if (root.has("data") && root.get("data").isArray() && root.get("data").size() > 0) {
                JsonNode first = root.get("data").get(0);
                if (first.has("embedding")) embNode = first.get("embedding");
            }
            if (embNode == null && root.has("embedding")) embNode = root.get("embedding");
            if (embNode == null && root.has("data") && root.get("data").isArray()) {
                // sometimes embedding under data[0].result.embedding
                JsonNode first = root.get("data").get(0);
                if (first.has("result") && first.get("result").has("embedding")) embNode = first.get("result").get("embedding");
            }

            if (embNode == null || !embNode.isArray()) {
                log.error("Cannot find embedding in response: {}", root.toString());
                return null;
            }

            float[] vec = new float[embNode.size()];
            for (int i = 0; i < embNode.size(); i++) {
                vec[i] = (float) embNode.get(i).asDouble();
            }
            // normalize for cosine
            return VectorUtils.normalize(vec);

        } catch (Exception e) {
            log.error("Embedding request failed", e);
            return null;
        }
    }

    private static class Payload {
        public String model;
        public String input;
        public Payload(String m, String i) {model = m; input = i;}
    }
}
