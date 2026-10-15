package com.cmbchina.llm.controller;

import com.cmbchina.llm.tool.GenerateEmbeddingTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read raw kb.csv -> call embedding service -> produce kb_with_embedding.csv
 */
@RestController
@Slf4j
public class GenerateEmbeddingsController {
    @Autowired
    private GenerateEmbeddingTool generateEmbeddingTool;

    @PostMapping("/admin/generate-embeddings")
    public Map<String, Object> generate() {
        log.info("GenerateEmbeddingsController.generate");
        return generateEmbeddingTool.generate();
    }


}
