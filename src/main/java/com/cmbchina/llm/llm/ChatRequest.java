package com.cmbchina.llm.llm;

import lombok.Getter;

/**
 * 统一聊天请求，支持消息 DTO 和保持原序列化结果的 JSON 请求。
 *
 * @author it0069
 * @date 2026-11-06 14:46:31
 */
@Getter
public class ChatRequest {

    /**
     * 业务调用场景，仅用于日志标识，不写入模型请求。
     */
    private final String scenario;

    /**
     * 包含模型参数和消息列表的请求，沿用 Jackson 序列化。
     */
    private final LlmRequest messagesRequest;

    /**
     * 已序列化的原始请求，保留自定义字段和原有 Fastjson 行为。
     */
    private final String requestJson;

    /**
     * 是否按原代码规划契约提取文本；原始响应场景交给业务解析器。
     */
    private final boolean contentRequired;

    private ChatRequest(String scenario, LlmRequest messagesRequest, String requestJson,
                        boolean contentRequired) {
        this.scenario = scenario;
        this.messagesRequest = messagesRequest;
        this.requestJson = requestJson;
        this.contentRequired = contentRequired;
    }

    public static ChatRequest forContent(String scenario, LlmRequest messagesRequest) {
        return new ChatRequest(scenario, messagesRequest, null, true);
    }

    public static ChatRequest forRawResponse(String scenario, String requestJson) {
        return new ChatRequest(scenario, null, requestJson, false);
    }
}
