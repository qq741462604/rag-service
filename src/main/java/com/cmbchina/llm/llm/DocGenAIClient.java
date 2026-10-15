package com.cmbchina.llm.llm;

import com.alibaba.fastjson.JSONObject;
import com.cmbchina.llm.exception.MyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用极算平台
 */
@Slf4j
@Component
public class DocGenAIClient {

    private HttpHeaders headers = new HttpHeaders();

    @Value("${docllm.api.url}")
    private String apiUrl;

    @Value("${docllm.api.key:}")
    private String apiKey;

    @Value("${docllm.api.model:qwen-plus}")
    private String modelName;

    @Autowired
    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"));
        headers.set("Authorization", "Bearer " + apiKey);
    }

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

            // 深度思考内容占token较多，关闭深度思考功能,参数设为false
            Map<String, Object> deepThinkType = new HashMap<>();
            deepThinkType.put("enable_thinking", false);

            Map<String, Object> req = new HashMap<>();
            req.put("model", modelName);
            req.put("chat_template_kwargs", deepThinkType);
            req.put("messages", messages);

            return queryLLM(req);

        } catch (Exception e) {
            throw new MyException("调用 LLM 失败");
        }
    }


    public String queryLLM(Map<String, Object> req) {
        HttpEntity<String> requestEntity = new HttpEntity<>(JSONObject.toJSONString(req), headers);
        String res = "";
        try {
            ResponseEntity<JSONObject> response = restTemplate.exchange(apiUrl, HttpMethod.POST, requestEntity, JSONObject.class);
            JSONObject responseBody = response.getBody();
            if (responseBody != null) {
                res = responseBody.toJSONString();
            }
        } catch (Exception e) {
            log.error("文档大模型调用异常：{}", e);
            throw new MyException("文档大模型调用异常");
        }

        return res;
    }
}
