package com.cmbchina.llm.controller;


import com.cmbchina.llm.handle.LlmHandle;
import com.cmbchina.llm.entity.model.DocContentDTO;
import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.cmbchina.llm.render.RenderStrategy;
import com.cmbchina.llm.service.DocContentAssembler;
import com.cmbchina.llm.service.DocGenerateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Paths;

@RestController
@RequestMapping("/doc")
@Slf4j
public class DocGenerateController {

    private final DocGenerateService service = new DocGenerateService();

    @Autowired
    private LlmHandle llmHandle;

    @Autowired
    private DocContentAssembler assembler;

    /**
     * 生成文档-手动拼报文
     * @param input
     * @param strategy
     * @return
     * @throws Exception
     */
    @PostMapping("/generate")
    public Object generate(
            @RequestBody Iteration1ResultDTO input,
            @RequestParam(defaultValue = "WORD") RenderStrategy strategy
    ) throws Exception {

        DocContentDTO docContent = assembler.generate(input);

        return service.generate(docContent, strategy, Paths.get("output"));
    }

    @PostMapping("/generate/task-id")
    public Object generateById(
            @RequestParam String taskId,
            @RequestParam(defaultValue = "WORD") RenderStrategy strategy
    ) throws Exception {

        if (taskId == null) {
            log.error("taskId为空");
            return null;
        }
        Iteration1ResultDTO input = null;
        try {
            // 直接获取Iteration1ResultDTO
            input = llmHandle.queryIteration1ResultById(taskId);
        } catch (Exception e) {
            log.error("大模型返回结果异常：{}", e);
            return "大模型返回结果异常";
        }

        DocContentDTO docContent = assembler.generate(input);

        return service.generate(docContent, strategy, Paths.get("output"));
    }

}
