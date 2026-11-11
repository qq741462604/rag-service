package com.cmbchina.llm.llm;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 修复补丁：有界解析公司网关 SSE，仅在 stop 与 DONE 均确认后交付完整模型正文。
 *
 * @author summer
 * @date 2026-11-11 00:00:00
 */
@Slf4j
final class SseChatResponseReader {

    private static final int MAX_EVENT_CHARS = 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 4 * 1024 * 1024;
    private static final long MAX_WIRE_CHARS = 32L * 1024 * 1024;

    private final ObjectMapper mapper;
    private final long startNanos;
    private final long timeoutNanos;
    private final StringBuilder content = new StringBuilder();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    // 修复补丁2：每个响应最多记录三个字段的元数据差异，避免按 token 刷日志。
    private final java.util.Set<String> metadataChanges = new java.util.LinkedHashSet<>();
    private JsonNode usage;
    private boolean stopped;
    private boolean done;
    private boolean errorEvent;
    private long wireChars;
    private int eventCount;
    private long firstEventMillis = -1;
    private long firstContentMillis = -1;
    private long lastProgressMillis;

    SseChatResponseReader(ObjectMapper mapper, long startNanos, int timeoutMillis) {
        this.mapper = mapper;
        this.startNanos = startNanos;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    JSONObject read(InputStream stream) throws IOException {
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        char[] buffer = new char[4096];
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        boolean previousCr = false;
        boolean firstChar = true;
        int count;
        while (!done) {
            checkActive();
            count = reader.read(buffer);
            checkActive();
            if (count < 0) {
                break;
            }
            wireChars += count;
            if (wireChars > MAX_WIRE_CHARS) {
                throw failure("流式响应超过累计接收上限");
            }
            if (elapsedMillis() - lastProgressMillis >= 15000) {
                lastProgressMillis = elapsedMillis();
                log.info("llm stream progress cost={}ms {}", lastProgressMillis, summary());
            }
            for (int i = 0; i < count && !done; i++) {
                char current = buffer[i];
                if (firstChar && current == '\uFEFF') {
                    firstChar = false;
                    continue;
                }
                firstChar = false;
                if (current == '\n' && previousCr) {
                    previousCr = false;
                    continue;
                }
                if (current == '\r' || current == '\n') {
                    acceptLine(line.toString(), data);
                    line.setLength(0);
                    previousCr = current == '\r';
                } else {
                    previousCr = false;
                    if (line.length() >= MAX_EVENT_CHARS) {
                        throw failure("SSE 单行超过接收上限");
                    }
                    line.append(current);
                }
            }
        }
        // EOF 不替代事件分隔和 DONE；中途断流绝不交付半份计划。
        if (!done || !stopped || content.toString().trim().isEmpty()) {
            throw failure("SSE 未完整结束，要求非空正文、finish_reason=stop 及 [DONE]");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content.toString());
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        metadata.put("object", "chat.completion");
        metadata.put("choices", Collections.singletonList(choice));
        if (usage != null) {
            metadata.put("usage", usage);
        }
        return JSONObject.parseObject(mapper.writeValueAsString(metadata));
    }

    private void acceptLine(String line, StringBuilder data) throws IOException {
        if (line.isEmpty()) {
            if (errorEvent) {
                throw failure("模型 SSE 返回错误事件");
            }
            if (data.length() > 0) {
                acceptEvent(data.toString());
                data.setLength(0);
            }
            errorEvent = false;
            return;
        }
        if (line.charAt(0) == ':') {
            return;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("event".equals(field)) {
            errorEvent = "error".equalsIgnoreCase(value);
        } else if ("data".equals(field)) {
            if ((long) data.length() + value.length() + 1 > MAX_EVENT_CHARS) {
                throw failure("SSE 单事件超过接收上限");
            }
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value);
        }
    }

    private void acceptEvent(String data) throws IOException {
        checkActive();
        eventCount++;
        if (firstEventMillis < 0) {
            firstEventMillis = elapsedMillis();
            log.info("llm stream first event cost={}ms", firstEventMillis);
        }
        if ("[DONE]".equals(data.trim())) {
            if (!stopped) {
                throw failure("收到 DONE 但缺少正常 stop，拒绝不完整模型结果");
            }
            done = true;
            return;
        }
        JsonNode event;
        try {
            event = mapper.readTree(data);
        } catch (IOException exception) {
            // 不携带原始数据或解析异常中的源码片段。
            throw failure("SSE data 不是合法 JSON");
        }
        if (event == null || !event.isObject() || event.hasNonNull("error")) {
            throw failure("模型 SSE 返回异常响应");
        }
        // 修复补丁2：id 用于检查响应归属，model/created 是元数据，不能等同于混流判据。
        collectMetadata(event);
        if (event.path("usage").isObject()) {
            usage = event.get("usage");
        }
        JsonNode choices = event.get("choices");
        if (choices == null || !choices.isArray()) {
            throw failure("SSE 缺少 choices 数组");
        }
        for (JsonNode choice : choices) {
            if (!choice.path("index").isIntegralNumber() || choice.path("index").asInt() != 0) {
                throw failure("SSE 返回非预期 choice，仅允许 index=0");
            }
            JsonNode delta = choice.path("delta");
            if ((delta.hasNonNull("tool_calls") && !delta.path("tool_calls").isEmpty())
                    || delta.hasNonNull("function_call") || delta.hasNonNull("refusal")) {
                throw failure("SSE 返回工具调用或拒绝，不能作为代码计划");
            }
            JsonNode text = delta.get("content");
            if (text != null && !text.isNull()) {
                if (!text.isTextual() || (stopped && !text.asText().isEmpty())) {
                    throw failure("SSE 正文字段异常或 stop 后仍有正文");
                }
                String fragment = text.asText();
                if ((long) content.length() + fragment.length() > MAX_CONTENT_CHARS) {
                    throw failure("模型正文超过聚合上限");
                }
                if (!fragment.isEmpty() && firstContentMillis < 0) {
                    firstContentMillis = elapsedMillis();
                }
                content.append(fragment);
            }
            // reasoning/reasoning_content 不是最终答案，不拼入代码计划。
            if (choice.hasNonNull("finish_reason")) {
                if (!"stop".equals(choice.path("finish_reason").asText())) {
                    throw failure("SSE 非正常结束（输出截断、过滤或工具调用），本计划未执行");
                }
                stopped = true;
            }
        }
    }

    /**
     * 修复补丁2：保留首个有效元数据；容忍网关模型别名/分块时间变化，不拼接不同非空 id。
     * 比较标量文本，避免相同时间戳因 Jackson 数值节点类型不同被误判。
     */
    private void collectMetadata(JsonNode event) throws IOException {
        for (String key : new String[]{"id", "model", "created"}) {
            JsonNode current = event.get(key);
            if (current == null || current.isNull()
                    || (current.isTextual() && current.asText().trim().isEmpty())) {
                continue;
            }
            if (("created".equals(key) && !current.isIntegralNumber() && !current.isTextual())
                    || (!"created".equals(key) && !current.isTextual())) {
                throw failure("SSE 元数据类型异常 field=" + key);
            }
            Object previous = metadata.get(key);
            if (previous == null) {
                metadata.put(key, current);
                continue;
            }
            if (((JsonNode) previous).asText().equals(current.asText())) {
                continue;
            }
            if ("id".equals(key)) {
                throw failure("SSE 响应标识不一致 field=id；同一连接出现不同非空响应 id，"
                        + "请核实网关是否重写分块 id 或混入其它响应");
            }
            if (metadataChanges.add(key)) {
                log.warn("修复补丁2 llm stream metadata changed field={} event={} action=keep-first-and-continue",
                        key, eventCount);
            }
        }
    }

    private void checkActive() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw failure("模型流接收已取消");
        }
        if (System.nanoTime() - startNanos >= timeoutNanos) {
            throw failure("模型流接收超过总时限");
        }
    }

    private IOException failure(String message) {
        return new IOException(message + "；已接收事件=" + eventCount + "，正文字符=" + content.length()
                + "，未交付模型计划");
    }

    long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    String summary() {
        return "events=" + eventCount + " contentChars=" + content.length() + " wireChars=" + wireChars
                + " firstEventMs=" + firstEventMillis + " firstContentMs=" + firstContentMillis
                + " metadataChangedFields=" + metadataChanges
                + " finishReason=" + (stopped ? "stop" : "missing") + " done=" + done;
    }
}
