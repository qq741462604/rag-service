package com.cmbchina.llm.llm;


import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 代码生成开发助手
 * 模型使用极算平台
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QwenClient implements LlmClient {

    private HttpHeaders headers = new HttpHeaders();

    private final ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;


    @Value("${llm.api.url}")
    private String url;


    @Value("${llm.api.key}")
    private String apiKey;


    @Value("${llm.model:qwen-plus}")
    private String model;

    @PostConstruct
    public void init() {
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"));
        headers.set("Authorization", "Bearer " + apiKey);
    }


    @Override
    public String chat(
            String prompt
    ) {


        try {
            long start = System.currentTimeMillis();
            log.info(
                    "qwen request model=【{}】, promptLength={}",
                    model,
                    prompt == null ? 0 : prompt.length()
            );


            LlmRequest request = new LlmRequest();


            request.setModel(model);

            // 深度思考内容占token较多，关闭深度思考功能,参数设为false
            Map<String, Object> deepThinkType = new HashMap<>();
            deepThinkType.put("enable_thinking", false);
            request.setChatTemplateKwargs(deepThinkType);

            request.setMessages(
                    Collections.singletonList(
                            new LlmRequest.Message(
                                    "user",
                                    prompt
                            )
                    )
            );


            String json = objectMapper.writeValueAsString(request);


            String body = "";
            HttpEntity<String> requestEntity = new HttpEntity<>(json, headers);

            // 超时不能证明服务端未计费，不自动重放整份代码生成请求。
            // 额度、鉴权和网络异常向任务层透传并停止，不进行内外两层重试。
            ResponseEntity<JSONObject> response = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, JSONObject.class);
            if (response.getBody() != null) {
                body = response.getBody().toJSONString();
            }

            log.info(
                    "qwen response cost={}ms bodyLength={}",
                    System.currentTimeMillis() - start,
                    body.length()
            );


            JsonNode node = objectMapper.readTree(body);
            JsonNode usage = node.path("usage");
            if (usage.isMissingNode() && node.path("output").has("usage")) {
                usage = node.path("output").path("usage");
            }
            if (!usage.isMissingNode()) {
                long inputTokens = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(-1));
                long outputTokens = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(-1));
                long totalTokens = usage.path("total_tokens").asLong(
                        inputTokens >= 0 && outputTokens >= 0 ? inputTokens + outputTokens : -1);
                log.info("qwen usage model={} inputTokens={} outputTokens={} totalTokens={}",
                        model, inputTokens, outputTokens, totalTokens);
            } else {
                log.info("qwen usage unavailable model={} (promptLength is characters, not tokens)", model);
            }



            /*
             Qwen格式:

             output.text

             或

             choices[0].message.content

             */


            if (node.has("output")) {

                JsonNode output = node.get("output");


                if (output.has("text")) {

                    return output
                            .get("text")
                            .asText();

                }


                if (output.isTextual()) {

                    return output.asText();

                }

            }


            if (node.has("choices")) {

                return node
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("content")
                        .asText();

            }


            return body;


        } catch (Exception e) {
            log.error(
                    "qwen call failed url={} model={}",
                    url,
                    model,
                    e
            );

            throw new RuntimeException(
                    "call qwen failed",
                    e
            );

        }


    }


}
