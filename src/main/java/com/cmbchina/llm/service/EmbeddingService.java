package com.cmbchina.llm.service;

import com.cmbchina.llm.util.VectorUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;

/**
 * 极算平台
 */
@Service
@Slf4j
public class EmbeddingService {

    private static final String EMBEDDING = "embedding";
    private static final String RESULT = "result";
    @Value("${dashscope.api.url}")
    private String apiUrl;

    @Value("${dashscope.api.key:}")
    private String apiKey;

    @Value("${dashscope.api.model}")
    private String modelName;

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpHeaders headers = new HttpHeaders();

    @PostConstruct
    public void init() {
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"));
        headers.set("Authorization", "Bearer " + apiKey);
    }


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

            String res = queryLLM(body);

            JsonNode root = mapper.readTree(res);

            // try several common paths
            JsonNode embNode = null;
            if (root.has("data") && root.get("data").isArray() && root.get("data").size() > 0) {
                JsonNode first = root.get("data").get(0);
                if (first.has(EMBEDDING)) {
                    embNode = first.get(EMBEDDING);
                }
            }
            if (embNode == null && root.has(EMBEDDING)) {
                embNode = root.get(EMBEDDING);
            }
            if (embNode == null && root.has("data") && root.get("data").isArray()) {
                // sometimes embedding under data[0].result.embedding
                JsonNode first = root.get("data").get(0);
                if (first.has(RESULT) && first.get(RESULT).has(EMBEDDING)) {
                    embNode = first.get(RESULT).get(EMBEDDING);
                }
            }

            if (embNode == null || !embNode.isArray()) {
                log.error("Cannot find embedding in response: {}", res);
                return null;
            }

            float[] vec = new float[embNode.size()];
            for (int i = 0; i < embNode.size(); i++) {
                vec[i] = (float)embNode.get(i).asDouble();
            }
            // normalize for cosine
            return VectorUtils.normalize(vec);

        } catch (Exception e) {
            log.error("Embedding request failed", e);
            return null;
        }
    }

    public String queryLLM(String req) {
        HttpEntity<String> requestEntity = new HttpEntity<>(req, headers);
        try {
            return restTemplate.postForObject(apiUrl, requestEntity, String.class);
        } catch (Exception e) {
            log.error("大模型调用异常：{}", e);
        }
        return "";
    }

    private static class Payload {
        public String model;
        public String input;

        public Payload(String m, String i) {
            model = m;
            input = i;
        }
    }
}