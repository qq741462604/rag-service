package com.cmbchina.llm.handle;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;

/**
 * 大模型调用处理器-调用AI智能工厂
 *
 * @author IT006901
 * @date 2025/11/12
 */
@Configuration
@Slf4j
public class LlmHandle {
    private HttpHeaders headers = new HttpHeaders();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.url}")
    private String llmUrl;

    @Value("${llm.async-url}")
    private String asyncLlmUrl;

    @Value("${llm.async-query-url}")
    private String asyncQueryLlmUrl;

    @Value("${llm.API-Key}")
    private String llmApikey;

    @Value("${llm.actionKey}")
    private String actionKey;

    @Autowired
    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        headers.setContentType(MediaType.parseMediaType("application/json;charset=utf-8"));
        headers.set("API-Key", llmApikey);
    }

    /**
     * 异步调用大模型
     *
     * @param object 输入参数
     * @return taskId
     */
    public String asyncQueryLLM(JSONObject object) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("inputParams", object);
        jsonObject.put("openId", "D9EA0722809ED9FB370D3E18C14E2587");
        jsonObject.put("actionType", "workflow");
        jsonObject.put("actionKey", actionKey);
        log.info("异步大模型输入参数：{}", jsonObject.toJSONString());
        HttpEntity<String> requestEntity = new HttpEntity<>(jsonObject.toJSONString(), headers);
        String taskId = "";
        try {
            ResponseEntity<JSONObject> response = restTemplate.exchange(asyncLlmUrl, HttpMethod.POST, requestEntity, JSONObject.class);
            JSONObject responseBody = response.getBody();
            if (responseBody == null) {
                log.error("异步大模型{}调用返回结果为空", asyncLlmUrl);
                return taskId;
            }
            taskId = responseBody.getJSONObject("body").getString("taskId");
        } catch (Exception e) {
            log.error("异步大模型调用异常：{}", e);
        }

        return taskId;
    }

    /**
     * 根据taskId查询大模型结果
     *
     * @param taskId 任务ID
     * @return Iteration1ResultDTO
     */
    public Iteration1ResultDTO queryIteration1ResultById(String taskId) {
        // 把taskId替换成到url的占位符
        String url = asyncQueryLlmUrl.replace("{}", taskId);
        log.info("大模型查询地址：{}", url);
        HttpEntity<String> requestEntity = new HttpEntity<>(headers);
        Iteration1ResultDTO result = new Iteration1ResultDTO();
        try {
            ResponseEntity<JSONObject> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, JSONObject.class);
            JSONObject responseBody = response.getBody();
            if (responseBody == null) {
                log.error("大模型{}调用返回结果为空", asyncQueryLlmUrl);
                return result;
            }
            JSONObject body = responseBody.getJSONObject("body");

            if (body != null && body.getJSONArray("actionResults") != null) {
                JSONArray r = body.getJSONArray("actionResults");
                JSONObject res = r.getJSONObject(0).getJSONObject("result").getJSONObject("output");
                result = handleResToDTO(res);
            }

        } catch (Exception e) {
            log.error("大模型返回结果异常：{}", e);
        }
        return result;
    }

    /**
     * 同步调用大模型
     *
     * @param object 输入参数
     * @return Iteration1ResultDTO
     */
    public Iteration1ResultDTO queryIteration1Result(JSONObject object) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("inputParams", object);
        jsonObject.put("openId", "D9EA0722809ED9FB370D3E18C14E2587");
        log.info("大模型输入参数：{}", jsonObject.toJSONString());
        return getIteration1Result(jsonObject);
    }

    /**
     * 获取大模型结果
     *
     * @param sendData 请求数据
     * @return Iteration1ResultDTO
     */
    public Iteration1ResultDTO getIteration1Result(JSONObject sendData) {
        HttpEntity<String> requestEntity = new HttpEntity<>(sendData.toJSONString(), headers);
        Iteration1ResultDTO result = new Iteration1ResultDTO();
        try {
            ResponseEntity<JSONObject> response = restTemplate.exchange(llmUrl, HttpMethod.POST, requestEntity, JSONObject.class);
            JSONObject responseBody = response.getBody();
            if (responseBody != null && responseBody.getJSONObject("body") != null) {
                JSONObject res = responseBody.getJSONObject("body").getJSONObject("output");
                result = handleResToDTO(res);
            }

        } catch (Exception e) {
            log.error("大模型返回结果异常：{}", e);
        }
        return result;
    }/**
     * 处理大模型返回结果，转换为Iteration1ResultDTO
     *
     * @param res 大模型返回的JSON
     * @return Iteration1ResultDTO
     */
    public Iteration1ResultDTO handleResToDTO(JSONObject res) {
        Iteration1ResultDTO dto = new Iteration1ResultDTO();
        log.debug("大模型返回结果：{}", res);

        // 处理SQL
        String sql = res.getString("createSql");
        if (sql != null) {
            // 使用正则表达式剔除换行符和制表符
            String processedString = sql.replaceAll("[\n\t]", "");
            dto.setCreateSql(processedString);
        }

        // 处理llmReason
        dto.setLlmReason(res.getString("llmReason"));

        // 处理llmResult
        String llmOut = res.getString("llmOut");
        if (llmOut != null) {
            llmOut = llmOut.replaceAll("[\n\t]", "");
            String llmOutJson = "{\"llmOut\": " + llmOut + "}";
            try {
                Iteration1ResultDTO.LlmResult llmResult = objectMapper.readValue(llmOutJson, Iteration1ResultDTO.LlmResult.class);
                dto.setLlmResult(llmResult);
            } catch (Exception e) {
                log.error("解析llmOut失败：{}", e);
            }
        }

        // 处理eventPost
        String eventSource = res.getString("eventSourcesPost");
        if (eventSource != null) {
            try {
                Iteration1ResultDTO.EventPost eventPost = objectMapper.readValue(eventSource, Iteration1ResultDTO.EventPost.class);
                dto.setEventPost(eventPost);
            } catch (Exception e) {
                log.error("解析eventSourcesPost失败：{}", e);
            }
        }

        // 处理eventMappingsPost
        String dimMapping = res.getString("eventSourcesMappingPost");
        if (dimMapping != null) {
            String dimMappingJson = "{\"eventFieldMappings\": " + dimMapping + "}";
            try {
                Iteration1ResultDTO.EventMappingsPost eventMappingsPost = objectMapper.readValue(dimMappingJson, Iteration1ResultDTO.EventMappingsPost.class);
                dto.setEventMappingsPost(eventMappingsPost);
            } catch (Exception e) {
                log.error("解析eventSourcesMappingPost失败：{}", e);
            }
        }

        return dto;
    }
}