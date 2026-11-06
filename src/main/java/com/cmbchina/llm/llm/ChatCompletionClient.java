package com.cmbchina.llm.llm;

import com.alibaba.fastjson.JSONObject;
import com.cmbchina.llm.config.ChatModelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

/**
 * 大语言模型公共传输层，业务角色自行构造请求并选择原始响应或模型文本。
 *
 * @author it0069
 * @date 2026-11-04 17:29:05
 */
@Slf4j
@Component
public class ChatCompletionClient implements LlmClient {

    private final ChatModelProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public ChatCompletionClient(ChatModelProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 聊天超时独立配置，不向容器新增 RestTemplate Bean，避免影响向量及工作流调用。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getReadTimeoutMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public ChatResponse chat(ChatRequest request) throws IOException {
        String json = request.isContentRequired()
                ? objectMapper.writeValueAsString(request.getMessagesRequest()) : request.getRequestJson();
        String model = requestModel(json);
        JSONObject responseBody = exchange(request.getScenario(), json, model);
        String body = responseBody == null ? "" : responseBody.toJSONString();
        return readResponse(request.getScenario(), model, responseBody, body, request.isContentRequired());
    }

    private JSONObject exchange(String role, String requestJson, String model) {
        long start = System.currentTimeMillis();
        log.info("llm request role={} model={} requestLength={}",
                role, model, requestJson == null ? 0 : requestJson.length());
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/json;charset=utf-8"));
            headers.set("Authorization", "Bearer " + properties.getKey());
            HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);
            // 超时不能证明服务端未计费，各角色均保持单次调用，不自动重放请求。
            ResponseEntity<JSONObject> response = restTemplate.exchange(
                    properties.getUrl(), HttpMethod.POST, requestEntity, JSONObject.class);
            JSONObject responseBody = response.getBody();
            String body = responseBody == null ? "" : responseBody.toJSONString();
            log.info("llm response role={} model={} cost={}ms bodyLength={}",
                    role, model, System.currentTimeMillis() - start, body.length());
            return responseBody;
        } catch (RuntimeException exception) {
            log.error("llm call failed role={} model={} cost={}ms errorType={}",
                    role, model, System.currentTimeMillis() - start, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private String extractText(String body) throws IOException {
        JsonNode node = objectMapper.readTree(body);
        // 保留代码规划原有提取顺序和兜底行为，文档角色不经过这一步。
        if (node.has("output")) {
            JsonNode output = node.get("output");
            if (output.has("text")) {
                return output.get("text").asText();
            }
            if (output.isTextual()) {
                return output.asText();
            }
        }
        if (node.has("choices")) {
            return node.get("choices").get(0).get("message").get("content").asText();
        }
        return body;
    }

    private String requestModel(String requestJson) {
        try {
            JSONObject request = JSONObject.parseObject(requestJson);
            if (request != null && request.getString("model") != null) {
                return request.getString("model");
            }
        } catch (RuntimeException exception) {
            // 日志元数据解析失败不影响原始请求发送。
        }
        return properties.getModel();
    }

    private ChatResponse readResponse(String role, String model, JSONObject responseBody, String body,
                                      boolean contentRequired) throws IOException {
        long inputTokens = -1;
        long outputTokens = -1;
        long totalTokens = -1;
        boolean usageAvailable = false;
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode usage = node.path("usage");
            if (usage.isMissingNode() && node.path("output").has("usage")) {
                usage = node.path("output").path("usage");
            }
            if (!usage.isMissingNode()) {
                inputTokens = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(-1));
                outputTokens = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(-1));
                totalTokens = usage.path("total_tokens").asLong(
                        inputTokens >= 0 && outputTokens >= 0 ? inputTokens + outputTokens : -1);
                log.info("llm usage role={} model={} inputTokens={} outputTokens={} totalTokens={}",
                        role, model, inputTokens, outputTokens, totalTokens);
                usageAvailable = true;
            }
        } catch (Exception exception) {
            // token 统计只用于观测，不能改变业务响应或触发重试。
        }
        if (!usageAvailable) {
            log.info("llm usage unavailable role={} model={} (lengths are characters, not tokens)", role, model);
        }
        String content = contentRequired ? extractText(body) : null;
        return new ChatResponse(responseBody, body, content, inputTokens, outputTokens, totalTokens);
    }
}
