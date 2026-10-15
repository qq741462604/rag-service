package com.cmbchina.llm.service;

import com.alibaba.fastjson.JSONObject;
import com.cmbchina.llm.entity.model.RequirementDoc;
import com.cmbchina.llm.transformer.DocContentParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求文档解析服务
 * 将人工填写的MD需求文档发送给大模型解析，生成RequirementDoc结构化数据
 *
 * @author IT006901
 * @date 2026-08-14 00:30:00
 */
@Slf4j
@Service
public class RequirementParseService {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${docllm.api.url}")
    private String llmUrl;

    @Value("${docllm.api.key}")
    private String llmApikey;

    @Value("${docllm.api.model:qwen-plus}")
    private String modelName;

    private HttpHeaders headers = new HttpHeaders();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocContentParser parser = new DocContentParser();

    @PostConstruct
    public void init() {
        headers.setContentType(MediaType.parseMediaType("application/json;charset=utf-8"));
        headers.set("Authorization", "Bearer " + llmApikey);
    }

    /**
     * 解析MD需求文档
     * 将MD文档内容发送给大模型，返回结构化的RequirementDoc
     *
     * @param mdContent MD文档内容
     * @return RequirementDoc结构化数据
     */
    public RequirementDoc parseRequirementDoc(String mdContent) {
        try {
            // 调用大模型
            JSONObject result = callLlm(mdContent);

            if (result == null) {
                log.error("大模型返回结果为空");
                return null;
            }

            // 解析大模型返回结果为RequirementDoc
            RequirementDoc doc = parseResultToDoc(result);

            log.info("需求文档解析成功：事件源ID={}",
                    doc.getBasicInfo().getEventSourceId());

            return doc;

        } catch (Exception e) {
            log.error("需求文档解析失败：{}", e);
            return null;
        }
    }


    /**
     * 构造解析提示词
     *
     * @return 提示词
     */
    private String buildParsePrompt(String parsedData) {
        StringBuilder sb = new StringBuilder();
        sb.append("请解析用户提供的天启需求MD文档，提取以下结构化信息并严格按照JSON格式输出：\n\n");
        sb.append("下面是MD文档内容：\n");
        sb.append(parsedData);
        sb.append("输出JSON格式要求：\n");
        sb.append("{\n");
        sb.append("  \"basicInfo\": {\n");
        sb.append("    \"eventSourceId\": \"事件源ID\",\n");
        sb.append("    \"eventSourceName\": \"事件源名称\",\n");
        sb.append("    \"dataRetentionTime\": \"数据保存时间\"\n");
        sb.append("  },\n");
        sb.append("  \"eventSourceFields\": [\n");
        sb.append("    {\n");
        sb.append("      \"name\": \"字段名\",\n");
        sb.append("      \"description\": \"字段描述\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"dimensionInfos\": [\n");
        sb.append("    {\n");
        sb.append("      \"dimensionName\": \"维度名称\",\n");
        sb.append("      \"tableName\": \"表名\",\n");
        sb.append("      \"partitionKey\": \"分区键字段\",\n");
        sb.append("      \"primaryKeys\": [\"主键字段1\", \"主键字段2\"],\n");
        sb.append("      \"retainFields\": [\"保留字段1\", \"保留字段2\"]\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"pluginRequirement\": {\n");
        sb.append("    \"decisionCodeRelation\": {\n");
        sb.append("      \"defaultValue\": 1,\n");
        sb.append("      \"rules\": [\n");
        sb.append("        {\n");
        sb.append("          \"code\": 1,\n");
        sb.append("          \"description\": \"通过\",\n");
        sb.append("          \"minScore\": -2147483648,\n");
        sb.append("          \"maxScore\": 20000\n");
        sb.append("        }\n");
        sb.append("      ]\n");
        sb.append("    },\n");
        sb.append("    \"changePoints\": [\n");
        sb.append("      {\n");
        sb.append("        \"name\": \"改造点名称\",\n");
        sb.append("        \"type\": \"数据加工\",\n");
        sb.append("        \"description\": \"描述\",\n");
        sb.append("        \"inputFields\": [\"输入字段\"],\n");
        sb.append("        \"outputFields\": [\"输出字段\"],\n");
        sb.append("        \"logic\": \"加工逻辑\",\n");
        sb.append("        \"externalApi\": {\n");
        sb.append("          \"url\": \"接口地址\",\n");
        sb.append("          \"method\": \"POST\",\n");
        sb.append("          \"requestParams\": \"请求参数JSON\",\n");
        sb.append("          \"responseParams\": \"响应参数JSON\"\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    ]\n");
        sb.append("  },\n");
        sb.append("  \"remarks\": \"补充说明\"\n");
        sb.append("}\n\n");
        sb.append("注意事项：\n");
        sb.append("1. 严格按照上述JSON格式输出，不要输出其他内容\n");
        sb.append("2. 字段名保持与模板一致\n");
        sb.append("3. eventSourceFields是事件源所有字段的列表，每个字段包含name和description，从MD文档的表格中解析\n");
        sb.append("4. dimensionInfos是以维度为一组的数组，每个维度包含tableName、partitionKey、primaryKeys、retainFields\n");
        sb.append("5. retainFields为选填字段，解析规则如下：\n");
        sb.append("   - 如果MD文档中\"保留字段\"行未填写内容（即该行为空），则retainFields返回空数组[]（表示默认保留事件源全部字段）\n");
        sb.append("   - 如果MD文档中\"保留字段\"行填写了all（不区分大小写），则retainFields返回空数组[]（表示默认保留事件源全部字段）\n");
        sb.append("   - 如果MD文档中\"保留字段\"行填写了具体字段列表（用逗号分隔），则retainFields必须返回这些字段的列表，每个字段保持MD文档中的原始写法（不转大写不转小写），例如：[\"AGC_NTB_NBR\", \"AGC_TYPE\", \"AGC_REQ_NBR\"]\n");
        sb.append("   - 特别注意：只要MD文档中\"保留字段\"行填写了任何具体字段名，就必须返回这些字段名列表，绝不能返回空数组或all\n");
        sb.append("6. decisionCodeRelation为选填，如果MD文档中未填写则为null\n");
        sb.append("7. 数组字段如果为空，返回空数组[]\n");
        sb.append("8. 字符串字段如果为空，返回空字符串\"\"\n");
        sb.append("9. 决策码值关系中的分数范围为整数\n");
        sb.append("10. 外调接口信息仅在改造点类型为\"外调接口\"时填写，否则externalApi为null\n");

        return sb.toString();
    }

    /**
     * 调用大模型
     *
     * @param mdContent 请求参数
     * @return 大模型返回结果
     */
    private JSONObject callLlm(String mdContent) {
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            String prompt = buildParsePrompt(mdContent);
            // 深度思考内容占token较多，关闭深度思考功能,参数设为false
            Map<String, Object> deepThinkType = new HashMap<>();
            deepThinkType.put("enable_thinking", false);

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            Map<String, Object> req = new HashMap<>();
            req.put("model", modelName);
            req.put("chat_template_kwargs", deepThinkType);
            req.put("messages", messages);

            HttpEntity<String> requestEntity = new HttpEntity<>(JSONObject.toJSONString(req), headers);
            ResponseEntity<JSONObject> response = restTemplate.exchange(llmUrl, HttpMethod.POST, requestEntity, JSONObject.class);

            return response.getBody();

        } catch (Exception e) {
            log.error("调用大模型异常：{}", e);
        }
        return null;
    }

    /**
     * 将大模型返回结果解析为RequirementDoc
     *
     * @param result 大模型返回的JSON
     * @return RequirementDoc对象
     */
    private RequirementDoc parseResultToDoc(JSONObject result) {
        try {
            // 从 LLM response 中取 content
            String docJson = parser.extractContent(result.toJSONString());
            return objectMapper.readValue(docJson, RequirementDoc.class);
        } catch (Exception e) {
            log.error("解析大模型返回结果为RequirementDoc失败：{}", e);
            log.error("大模型返回内容：{}", result.toJSONString());
            return null;
        }
    }
}