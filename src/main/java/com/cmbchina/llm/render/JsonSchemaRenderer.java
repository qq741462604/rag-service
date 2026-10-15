package com.cmbchina.llm.render;


import com.cmbchina.llm.entity.model.DocContentDTO;
import com.cmbchina.llm.service.JsonSchemaGenerator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class JsonSchemaRenderer implements DocRenderer {

    @Override
    public File render(DocContentDTO dto, Path outputDir) throws Exception {
        String filename = dto.getApiTitle() + "_request_schema.json";
        Path outputPath = outputDir.resolve(filename);

        String schema = JsonSchemaGenerator.generateRequestSchema(dto);
        Files.write(outputPath, schema.getBytes("UTF-8"));
        return outputPath.toFile();
    }
}
