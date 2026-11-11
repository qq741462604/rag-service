package com.cmbchina.llm.llm;

import com.cmbchina.llm.config.ChatModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 代码生成开发助手，保留规划请求及模型文本返回契约。
 */
@Component
@RequiredArgsConstructor
public class QwenClient {

    private final ChatModelProperties properties;
    private final LlmClient llmClient;

    public String chat(String prompt) {
        try {
            LlmRequest request = new LlmRequest();
            request.setModel(properties.getModel());
            // 修复补丁：流式只改变通信方式，仍返回完整文本后再解析和执行计划。
            if (properties.isCodeGenerationStreamEnabled()) {
                request.setStream(Boolean.TRUE);
            }
            Map<String, Object> deepThinkType = new HashMap<>();
            deepThinkType.put("enable_thinking", properties.isEnableThinking());
            request.setChatTemplateKwargs(deepThinkType);
            request.setThinking(Collections.singletonMap("type", properties.getThinkingType()));
            request.setReasoningEffort(properties.getReasoningEffort());
            request.setMessages(Collections.singletonList(new LlmRequest.Message("user", prompt)));

            // 继续使用 Jackson 序列化规划请求，不加入文档角色的 system 消息。
            return llmClient.chat(ChatRequest.forContent("code-generation", request)).getContent();
        } catch (Exception exception) {
            throw new RuntimeException("call qwen failed", exception);
        }
    }
}
