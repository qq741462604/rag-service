package com.cmbchina.llm.render;


import com.cmbchina.llm.entity.model.DocContentDTO;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MarkdownRenderer implements DocRenderer {

    @Override
    public File render(DocContentDTO dto, Path outputDir) throws Exception {
        String filename = dto.getApiTitle() + ".md";
        Path outputPath = outputDir.resolve(filename);

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(dto.getApiTitle()).append("\n\n");
        sb.append("## 请求结构定义\n\n");
        sb.append("| 字段名 | 描述 | 类型 | 长度 | 必填 | 备注 |\n");
        sb.append("|--------|------|------|------|------|------|\n");

        List<DocContentDTO.FieldDTO> fields = dto.getRequestFields();
        for (DocContentDTO.FieldDTO f : fields) {
            sb.append("|").append(f.getName()).append("|")
                    .append(f.getDescription()).append("|")
                    .append(f.getType()).append("|")
                    .append(f.getLength()).append("|")
                    .append(f.isRequired() ? "是" : "否").append("|")
                    .append(f.isNeedsReview() ? "人工确认" : "").append("|\n");
        }

        sb.append("\n## 请求示例\n\n```json\n")
                .append(dto.getRequestExample())
                .append("\n```\n");

        sb.append("\n## 建表语句\n\n```sql\n")
                .append(dto.getSql())
                .append("\n```\n");

        Files.write(outputPath, sb.toString().getBytes("UTF-8"));
        return outputPath.toFile();
    }
}
