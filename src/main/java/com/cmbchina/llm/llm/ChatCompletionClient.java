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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    // 修复补丁：共享守护线程监督总时限及取消；任务完成即移除，不新增应用线程池 Bean。
    private static final ScheduledThreadPoolExecutor STREAM_WATCHDOG = streamWatchdog();

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
        // 修复补丁：只让代码角色的显式 stream=true 进入 SSE，原始 JSON 角色保持原调用。
        boolean streaming = request.isContentRequired() && "code-generation".equals(request.getScenario())
                && Boolean.TRUE.equals(request.getMessagesRequest().getStream());
        JSONObject responseBody = streaming ? exchangeStream(request.getScenario(), json, model)
                : exchange(request.getScenario(), json, model);
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

    /** 修复补丁：在 ResponseExtractor 内消费 SSE，聚合完成后复用原 ChatResponse/usage 解析。 */
    private JSONObject exchangeStream(String role, String requestJson, String model) throws IOException {
        int totalMillis = properties.getStreamTotalTimeoutMillis();
        int idleMillis = properties.getStreamReadTimeoutMillis();
        if (totalMillis <= 0 || idleMillis <= 0 || properties.getConnectTimeoutMillis() <= 0) {
            throw new IOException("流式连接、读取及总时限必须为正数，本次模型请求未发送");
        }
        long startNanos = System.nanoTime();
        Thread owner = Thread.currentThread();
        AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        AtomicReference<String> aborted = new AtomicReference<>();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection current, String method) throws IOException {
                connection.set(current);
                super.prepareConnection(current, method);
                if (aborted.get() != null || owner.isInterrupted()) {
                    current.disconnect();
                    throw new IOException("模型流请求已取消或超时");
                }
            }
        };
        factory.setConnectTimeout(Math.min(properties.getConnectTimeoutMillis(), totalMillis));
        factory.setReadTimeout(Math.min(idleMillis, totalMillis));
        RestTemplate streamingTemplate = new RestTemplate(factory);
        SseChatResponseReader reader = new SseChatResponseReader(objectMapper, startNanos, totalMillis);
        ScheduledFuture<?> watchdog = STREAM_WATCHDOG.scheduleWithFixedDelay(() -> {
            String reason = owner.isInterrupted() ? "模型流接收已取消" :
                    System.nanoTime() - startNanos >= TimeUnit.MILLISECONDS.toNanos(totalMillis)
                            ? "模型流接收超过总时限" : null;
            if (reason != null && aborted.compareAndSet(null, reason)) {
                HttpURLConnection current = connection.get();
                if (current != null) {
                    current.disconnect();
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        log.info("llm request role={} model={} stream=true requestLength={} idleTimeoutMs={} totalTimeoutMs={}",
                role, model, requestJson.length(), idleMillis, totalMillis);
        try {
            JSONObject result = streamingTemplate.execute(properties.getUrl(), HttpMethod.POST, request -> {
                request.getHeaders().setContentType(MediaType.parseMediaType("application/json;charset=utf-8"));
                request.getHeaders().setAccept(Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
                request.getHeaders().set("Authorization", "Bearer " + properties.getKey());
                try (OutputStream body = request.getBody()) {
                    body.write(requestJson.getBytes(StandardCharsets.UTF_8));
                }
            }, response -> {
                MediaType type = response.getHeaders().getContentType();
                if (type == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
                    throw new IOException("模型流式接口未返回 text/event-stream，请核实网关协议；未自动降级重发");
                }
                return reader.read(response.getBody());
            });
            if (aborted.get() != null || owner.isInterrupted()) {
                throw new IOException(aborted.get() == null ? "模型流接收已取消" : aborted.get());
            }
            log.info("llm response role={} model={} stream=true cost={}ms {}",
                    role, model, reader.elapsedMillis(), reader.summary());
            return result;
        } catch (RuntimeException | IOException exception) {
            log.error("llm call failed role={} model={} stream=true cost={}ms errorType={} {}",
                    role, model, reader.elapsedMillis(), exception.getClass().getSimpleName(), reader.summary());
            if (aborted.get() != null) {
                // 不附加底层异常，避免最终根因遍历覆盖明确的取消/总超时原因。
                throw new IOException(aborted.get() + "；未交付模型计划，未自动重发");
            }
            throw exception;
        } finally {
            watchdog.cancel(false);
            HttpURLConnection current = connection.getAndSet(null);
            if (current != null) {
                current.disconnect();
            }
        }
    }

    private static ScheduledThreadPoolExecutor streamWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "llm-stream-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
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
                // 缓存统计完全采用服务商返回值；缺失时不推算，不影响响应解析或请求参数。
                long cachedInputTokens = usage.path("prompt_cache_hit_tokens").asLong(
                        usage.path("prompt_tokens_details").path("cached_tokens").asLong(-1));
                long uncachedInputTokens = usage.path("prompt_cache_miss_tokens").asLong(-1);
                if (cachedInputTokens >= 0 || uncachedInputTokens >= 0) {
                    log.info("llm input cache role={} model={} cachedInputTokens={} uncachedInputTokens={} (-1=unavailable)",
                            role, model, cachedInputTokens, uncachedInputTokens);
                }
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
