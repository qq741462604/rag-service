package com.cmbchina.llm.controller;

import com.alibaba.fastjson.JSONObject;
import com.cmbchina.llm.entity.InputEntity;
import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.cmbchina.llm.entity.model.RequirementDoc;
import com.cmbchina.llm.handle.LlmHandle;
import com.cmbchina.llm.service.ExcelService;
import com.cmbchina.llm.service.RequirementParseService;
import com.cmbchina.llm.service.TqDocService;
import com.cmbchina.llm.service.TqExcelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Excel/MD数据解析与大模型测试接口
 * 支持自动识别上传文件类型：Excel走原有解析流程，MD走需求文档解析流程
 *
 * @author IT006901
 * @date 2025/11/17
 */
@RestController
@Slf4j
public class ExcelController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ExcelService excelService;

    @Autowired
    private LlmHandle llmHandle;

    @Autowired
    private TqExcelService tqExcelService;

    @Autowired
    private TqDocService tqDocService;

    @Autowired
    private RequirementParseService requirementParseService;

    /**
     * 直接发送报文测试
     *
     * @param str
     * @return
     */
    @PostMapping("/test")
    public Iteration1ResultDTO test(@RequestBody InputEntity str) {
        try {
            // 将 InputEntity 转换为 JSON 字符串
            String jsonString = OBJECT_MAPPER.writeValueAsString(str);

            // 将 JSON 字符串转换为 JSONObject
            JSONObject jsonObject = JSONObject.parseObject(jsonString);
            return llmHandle.queryIteration1Result(jsonObject);
        } catch (Exception e) {
            // 处理异常
            log.error("大模型test返回结果异常：{}", e);
            return null;
        }
    }

    /**
     * 直接上传文件测试
     * 自动识别文件类型：
     * - Excel文件（.xlsx/.xls）→ 走原有Excel解析流程
     * - MD文件（.md）→ 走需求文档解析流程
     *
     * @param file 上传的文件（Excel或MD）
     * @return 解析结果（Iteration1ResultDTO或RequirementDoc）
     */
    @PostMapping("/upload-test")
    public Object uploadTest(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                log.warn("上传的文件为空");
                return null;
            }

            String fileName = file.getOriginalFilename();
            if (fileName == null) {
                log.warn("无法获取文件名");
                return null;
            }

            // 根据文件扩展名判断类型
            String lowerFileName = fileName.toLowerCase();
            if (lowerFileName.endsWith(".md")) {
                // MD文件 → 走需求文档解析流程
                log.info("识别为MD文件，走需求文档解析流程：{}", fileName);
                return parseMdFile(file);
            } else if (lowerFileName.endsWith(".xlsx") || lowerFileName.endsWith(".xls")) {
                // Excel文件 → 走原有Excel解析流程
                log.info("识别为Excel文件，走原有Excel解析流程：{}", fileName);
                return parseExcelFile(file);
            } else {
                log.warn("不支持的文件类型：{}", fileName);
                return "错误：不支持的文件类型，请上传.md或.xlsx文件";
            }

        } catch (Exception e) {
            log.error("大模型upload返回结果异常：{}", e);
            return null;
        }
    }

    /**
     * 解析Excel文件（原有流程）
     *
     * @param file Excel文件
     * @return Iteration1ResultDTO
     */
    private Iteration1ResultDTO parseExcelFile(MultipartFile file) throws Exception {
        String jsonOutput = excelService.parseExcelToJSON(file);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("input", JSONObject.parseObject(jsonOutput));
        return llmHandle.queryIteration1Result(jsonObject);
    }/**
     * 解析MD需求文档
     * 将MD中的事件源字段信息提取后，走大模型解析流程生成Iteration1ResultDTO
     * 同时返回RequirementDoc结构化数据
     *
     * @param file MD文件
     * @return RequirementDoc结构化数据
     */
    private RequirementDoc parseMdFile(MultipartFile file) throws Exception {
        // 读取MD文件内容
        String mdContent;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            mdContent = reader.lines().collect(Collectors.joining("\n"));
        }

        log.info("开始解析MD需求文档");
        RequirementDoc doc = requirementParseService.parseRequirementDoc(mdContent);

        if (doc != null) {
            log.info("MD需求文档解析成功：事件源ID={}", doc.getBasicInfo().getEventSourceId());

            // 如果MD中包含事件源字段信息，同时触发大模型解析生成Iteration1ResultDTO
            if (doc.getEventSourceFields() != null && !doc.getEventSourceFields().isEmpty()) {
                triggerLlmParsing(doc.getEventSourceFields());
            }
        } else {
            log.error("MD需求文档解析失败");
        }

        return doc;
    }

    /**
     * 根据事件源字段信息触发大模型解析
     * 将MD中的字段信息转换为与Excel解析相同的输入格式，调用大模型生成Iteration1ResultDTO
     *
     * @param eventSourceFields 事件源字段列表
     */
    private void triggerLlmParsing(List<RequirementDoc.EventSourceField> eventSourceFields) {
        try {
            // 将事件源字段转换为与Excel解析相同的JSON格式
            com.alibaba.fastjson.JSONArray jsonArray = new com.alibaba.fastjson.JSONArray();
            for (RequirementDoc.EventSourceField field : eventSourceFields) {
                JSONObject fieldJson = new JSONObject();
                fieldJson.put("name", field.getName());
                fieldJson.put("description", field.getDescription());
                jsonArray.add(fieldJson);
            }

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("input", jsonArray);

            // 异步调用大模型
            String taskId = llmHandle.asyncQueryLLM(jsonObject);
            log.info("MD事件源字段已触发大模型异步解析，taskId：{}", taskId);

        } catch (Exception e) {
            log.error("触发大模型解析异常：{}", e);
        }
    }/**
     * 异步上传文件测试
     * 自动识别文件类型：Excel走原有解析流程，MD走需求文档解析流程
     *
     * @param file 上传的文件（Excel或MD）
     * @return 异步任务ID或解析结果
     */
    @PostMapping("/upload-async-test")
    public String uploadAsyncTest(@RequestParam("file") MultipartFile file) {
        String result = "";
        try {
            if (file.isEmpty()) {
                return "错误：上传的文件为空";
            }

            String fileName = file.getOriginalFilename();
            if (fileName == null) {
                return "错误：无法获取文件名";
            }

            String lowerFileName = fileName.toLowerCase();
            if (lowerFileName.endsWith(".md")) {
                // MD文件 → 走需求文档解析流程
                log.info("异步识别为MD文件，走需求文档解析流程：{}", fileName);
                RequirementDoc doc = parseMdFile(file);
                if (doc != null) {
                    result = "MD需求文档解析成功，事件源ID：" + doc.getBasicInfo().getEventSourceId();
                } else {
                    result = "错误：MD需求文档解析失败";
                }
            } else if (lowerFileName.endsWith(".xlsx") || lowerFileName.endsWith(".xls")) {
                // Excel文件 → 走原有Excel解析流程
                log.info("异步识别为Excel文件，走原有Excel解析流程：{}", fileName);
                String jsonOutput = excelService.parseExcelToJSON(file);
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("input", JSONObject.parse(jsonOutput));
                result = llmHandle.asyncQueryLLM(jsonObject);
            } else {
                result = "错误：不支持的文件类型，请上传.md或.xlsx文件";
            }

        } catch (Exception e) {
            log.error("大模型返回结果异常：{}", e);
            result = "错误：大模型返回结果异常 - " + e.getMessage();
        }
        return result;
    }

    /**
     * 获取异步结果
     *
     * @param taskId
     * @return
     */
    @GetMapping("/query-llm-result/{taskId}")
    public Iteration1ResultDTO queryLLMResultById(@PathVariable String taskId) {
        Iteration1ResultDTO result = null;
        try {
            result = llmHandle.queryIteration1ResultById(taskId);
        } catch (Exception e) {
            log.error("大模型返回结果异常：{}", e);
        }
        return result;
    }/**
     * 生成天启输出文件
     * 根据taskId获取大模型解析结果，同时生成天启建表语句Excel和天启文档
     *
     * @param taskId 任务ID
     * @return 生成结果信息
     */
    @GetMapping("/tq-output/{taskId}")
    public String generateTqOutput(@PathVariable String taskId) {
        try {
            // 1. 获取大模型解析结果
            Iteration1ResultDTO iteration1Result = llmHandle.queryIteration1ResultById(taskId);
            if (iteration1Result == null || iteration1Result.getLlmResult() == null) {
                log.warn("大模型解析结果为空，taskId: {}", taskId);
                return "错误：大模型解析结果为空";
            }

            // 2. 从createSql中提取表名
            String tableName = extractTableName(iteration1Result.getCreateSql());
            if (tableName == null || tableName.isEmpty()) {
                log.warn("无法从createSql中提取表名，createSql: {}", iteration1Result.getCreateSql());
                return "错误：无法从createSql中提取表名";
            }

            StringBuilder resultBuilder = new StringBuilder();

            // 3. 生成天启Excel
            String excelFilePath = tqExcelService.generateTqExcel(iteration1Result, tableName);
            if (excelFilePath != null) {
                log.info("天启Excel生成成功，taskId: {}, 表名: {}, 文件路径: {}", taskId, tableName, excelFilePath);
                resultBuilder.append("天启Excel生成成功，文件路径：").append(excelFilePath).append("\n");
            } else {
                log.error("天启Excel生成失败，taskId: {}", taskId);
                resultBuilder.append("错误：天启Excel生成失败\n");
            }

            // 4. 生成天启文档
            String docFilePath = tqDocService.generateTqDoc(iteration1Result, tableName);
            if (docFilePath != null) {
                log.info("天启文档生成成功，taskId: {}, 表名: {}, 文件路径: {}", taskId, tableName, docFilePath);
                resultBuilder.append("天启文档生成成功，文件路径：").append(docFilePath);
            } else {
                log.error("天启文档生成失败，taskId: {}", taskId);
                resultBuilder.append("错误：天启文档生成失败");
            }

            return resultBuilder.toString();

        } catch (Exception e) {
            log.error("天启输出生成异常，taskId: {}", taskId, e);
            return "错误：天启输出生成异常 - " + e.getMessage();
        }
    }

    /**
     * 从createSql中提取表名
     *
     * @param createSql 建表语句
     * @return 表名
     */
    private String extractTableName(String createSql) {
        if (createSql == null || createSql.isEmpty()) {
            return null;
        }
        // 匹配 CREATE TABLE 后面的表名
        Pattern pattern = Pattern.compile("(?i)CREATE\\s+TABLE\\s+(\\w+)");
        Matcher matcher = pattern.matcher(createSql);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}