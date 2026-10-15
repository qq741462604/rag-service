package com.cmbchina.llm.controller;

import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.cmbchina.llm.entity.model.RequirementDoc;
import com.cmbchina.llm.handle.LlmHandle;
import com.cmbchina.llm.service.PluginChangeAsyncExecutor;
import com.cmbchina.llm.service.RequirementParseService;
import com.cmbchina.llm.service.TqDocService;
import com.cmbchina.llm.service.TqExcelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 需求文档解析接口
 * 接收人工填写的MD需求文档，调用大模型解析生成结构化数据
 * 并可基于解析结果直接生成天启输出文件
 *
 * @author IT006901
 * @date 2026-08-14 00:40:00
 */
@RestController
@RequestMapping("/requirement")
@Slf4j
public class RequirementController {

    @Autowired
    private RequirementParseService requirementParseService;

    @Autowired
    private TqExcelService tqExcelService;

    @Autowired
    private TqDocService tqDocService;

    @Autowired
    private LlmHandle llmHandle;

    @Autowired
    private PluginChangeAsyncExecutor pluginChangeAsyncExecutor;

    /**
     * 通过上传MD文件解析需求文档
     * 测试使用
     *
     * @param file MD文件
     * @return RequirementDoc结构化数据
     */
    @PostMapping("/parse-file")
    public RequirementDoc parseRequirementFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                log.warn("上传的MD文件为空");
                return null;
            }

            // 读取MD文件内容
            String mdContent;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                mdContent = reader.lines().collect(Collectors.joining("\n"));
            }

            log.info("开始解析MD需求文档，文件名：{}", file.getOriginalFilename());
            RequirementDoc doc = requirementParseService.parseRequirementDoc(mdContent);

            if (doc != null) {
                log.info("MD需求文档解析成功：事件源ID={}", doc.getBasicInfo().getEventSourceId());
            } else {
                log.error("MD需求文档解析失败");
            }

            return doc;

        } catch (Exception e) {
            log.error("解析MD需求文档异常：{}", e);
            return null;
        }
    }

/**
 * 上传MD文件，解析并生成天启输出文件（Excel + MD文档）
 * 流程：
 * 1. 根据taskId获取大模型解析结果Iteration1ResultDTO（提供字段类型等信息）
 * 2. 解析MD需求文档获取RequirementDoc（提供表名、分区键、主键等信息）
 * 3. 结合两者生成天启Excel，有几个维度就建几张表（Sheet页）
 * 4. 生成天启MD文档
 * 5. 异步提交插件改造任务（不阻塞主流程）
 *
 * @param taskId 大模型异步解析任务ID
 * @param file MD需求文档文件
 * @return 生成结果信息
 */
@PostMapping("/generate-tq-out")
public String generateTqOut(@RequestParam("taskId") String taskId,
                            @RequestParam("file") MultipartFile file) {
    try {
        // 1. 获取大模型解析结果
        Iteration1ResultDTO iteration1Result = llmHandle.queryIteration1ResultById(taskId);
        if (iteration1Result == null || iteration1Result.getLlmResult() == null) {
            log.warn("大模型解析结果为空，taskId: {}", taskId);
            return "错误：大模型解析结果为空";
        }

        // 2. 读取MD文件内容
        if (file.isEmpty()) {
            return "错误：上传的MD文件为空";
        }
        String mdContent;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            mdContent = reader.lines().collect(Collectors.joining("\n"));
        }

        log.info("开始解析MD需求文档并生成天启输出文件，文件名：{}", file.getOriginalFilename());

        // 3. 解析MD文档
        RequirementDoc doc = requirementParseService.parseRequirementDoc(mdContent);
        if (doc == null) {
            return "错误：MD需求文档解析失败";
        }

        log.info("MD需求文档解析成功：事件源ID={}", doc.getBasicInfo().getEventSourceId());

        // 4. 校验维度信息
        if (doc.getDimensionInfos() == null || doc.getDimensionInfos().isEmpty()) {
            return "错误：需求文档中没有维度字段信息";
        }
        String eventSourceId = doc.getBasicInfo().getEventSourceId();


        // 7. 异步执行插件改造任务（不阻塞主流程）
        pluginChangeAsyncExecutor.executeAsync(doc);
        log.info("插件改造异步任务已提交，事件源ID={}", eventSourceId);

        // 5. 结合Iteration1ResultDTO和RequirementDoc生成天启Excel
        String excelPath = tqExcelService.generateTqExcelCombined(iteration1Result, doc);
        if (excelPath == null) {
            return "错误：天启Excel生成失败";
        }

        int sheetCount = doc.getDimensionInfos().size();
        log.info("天启Excel生成成功，事件源ID：{}，维度数：{}，文件路径：{}",
                doc.getBasicInfo().getEventSourceId(), sheetCount, excelPath);

        // 6. 生成天启MD文档（只生成一份，包含所有维度的计算集）
        StringBuilder mdResult = new StringBuilder();

        mdResult.append("天启Excel生成成功，事件源ID：").append(eventSourceId)
                .append("，维度数：").append(sheetCount)
                .append("，文件路径：").append(excelPath).append("\n");

        String mdPath = tqDocService.generateTqDoc(iteration1Result, doc);
        if (mdPath != null) {
            mdResult.append("天启MD文档生成成功，事件源ID：").append(eventSourceId)
                    .append("，文件路径：").append(mdPath).append("\n");
        } else {
            mdResult.append("天启MD文档生成失败\n");
        }



        return mdResult.toString();

    } catch (Exception e) {
        log.error("生成天启输出文件异常：{}", e);
        return "错误：生成天启输出文件异常 - " + e.getMessage();
    }
}

}