package com.cmbchina.llm.llm;

import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 统一聊天结果，明确区分模型文本、原始响应和实际 token 用量。
 *
 * @author it0069
 * @date 2026-11-06 14:46:31
 */
@Getter
@RequiredArgsConstructor
public class ChatResponse {

    /**
     * 原始 JSON 对象，HTTP 响应体为空时保持 null。
     */
    private final JSONObject responseBody;

    /**
     * 完整响应 JSON，HTTP 响应体为空时为空字符串。
     */
    private final String rawResponse;

    /**
     * 按需提取的模型文本；请求原始响应时为 null。
     */
    private final String content;

    /**
     * 输入 token 数，服务端未提供时为 -1。
     */
    private final long inputTokens;

    /**
     * 输出 token 数，服务端未提供时为 -1。
     */
    private final long outputTokens;

    /**
     * 总 token 数，服务端未提供且无法计算时为 -1。
     */
    private final long totalTokens;
}
