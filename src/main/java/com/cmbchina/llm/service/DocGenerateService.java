package com.cmbchina.llm.service;



import com.cmbchina.llm.entity.model.DocContentDTO;
import com.cmbchina.llm.render.DocRenderer;
import com.cmbchina.llm.render.JsonSchemaRenderer;
import com.cmbchina.llm.render.MarkdownRenderer;
import com.cmbchina.llm.render.RenderStrategy;
import com.cmbchina.llm.render.WordDocRenderer;

import java.io.File;
import java.nio.file.Path;

public class DocGenerateService {

    public File generate(DocContentDTO dto, RenderStrategy strategy, Path outputDir) throws Exception {
        if (!outputDir.toFile().exists()) {
            outputDir.toFile().mkdirs();
        }

        DocRenderer renderer = selectRenderer(strategy);
        return renderer.render(dto, outputDir);
    }

    private DocRenderer selectRenderer(RenderStrategy strategy) {
        switch (strategy) {
            case WORD:
                /** 后面复用模板再启用 return new WordDocRenderer(Paths.get("docs/api-doc-template.docx"));*/
                return new WordDocRenderer();
            case MARKDOWN:
                return new MarkdownRenderer();
            case JSON_SCHEMA:
                return new JsonSchemaRenderer();
            default:
                throw new IllegalArgumentException("不支持的渲染策略：" + strategy);
        }
    }
}
