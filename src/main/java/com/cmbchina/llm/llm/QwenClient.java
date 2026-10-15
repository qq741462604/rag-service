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
import org.springframework.web.client.HttpServerErrorException;
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

            // 网关超时重试机制：总共最多调用3次模型（1次原始 + 2次重试）
            // 注意：网关 504 超时并不代表请求未被处理，重试会再次调用模型生成完整输出，
            // 因此重试次数必须严格控制，避免产生重复输出、浪费 token。
            // 模型生成完整代码（如 277 行 Handler）耗时较长（50s+），接近网关超时上限，
            // 偶发 504 需要多次重试才能成功。
            int maxRetry = 2;
            for (int attempt = 0; attempt <= maxRetry; attempt++) {
                try {
                    ResponseEntity<JSONObject> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, JSONObject.class);
                    if (response.getBody() != null) {
                        body = response.getBody().toJSONString();
                    }
                    break;
                } catch (HttpServerErrorException e) {
                    if (attempt < maxRetry && e.getStatusCode().value() == 504) {
                        log.warn("qwen request gateway timeout, retry {}/{} url={} model={}",
                                attempt + 1, maxRetry, url, model);
                        Thread.sleep(3000L * (attempt + 1)); // 递增等待：3s, 6s
                        continue;
                    }
                    throw e;
                }
            }


            log.info(
                    "qwen response cost={}ms bodyLength={}",
                    System.currentTimeMillis() - start,
                    body.length()
            );


            JsonNode node = objectMapper.readTree(body);



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
                    "qwen call failed url={} model={} timeout",
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
