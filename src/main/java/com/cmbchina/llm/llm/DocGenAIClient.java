package com.cmbchina.llm.llm;

import com.alibaba.fastjson.JSONObject;
import com.cmbchina.llm.config.ChatModelProperties;
import com.cmbchina.llm.exception.MyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档生成角色客户端，保留完整响应 JSON 供文档解析器处理。
 */
@Component
@RequiredArgsConstructor
public class DocGenAIClient {

    private final ChatModelProperties properties;
    private final LlmClient llmClient;

    public String chat(String prompt) {
        try {
            // ---------- 构造 messages ----------
            List<Map<String, Object>> messages = new ArrayList<>();

            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", "你是专业的文档生成助手");
            messages.add(systemMessage);

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            Map<String, Object> deepThinkType = new HashMap<>();
            deepThinkType.put("enable_thinking", properties.isEnableThinking());

            Map<String, Object> req = new HashMap<>();
            req.put("model", properties.getModel());
            req.put("chat_template_kwargs", deepThinkType);
            req.put("messages", messages);

            return queryLLM(req);

        } catch (Exception e) {
            throw new MyException("调用 LLM 失败", e);
        }
    }


    public String queryLLM(Map<String, Object> req) {
        String requestJson = JSONObject.toJSONString(req);
        try {
            // 公开入口仍原样发送调用方请求，不补写或覆盖 model/messages 等参数。
            return llmClient.chat(ChatRequest.forRawResponse("document-generation", requestJson)).getRawResponse();
        } catch (Exception e) {
            throw new MyException("文档大模型调用异常", e);
        }
    }
}
