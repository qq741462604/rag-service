package com.cmbchina.llm.tool;

import com.cmbchina.llm.service.EmbeddingService;
import com.cmbchina.llm.util.CSVUtils;
import com.cmbchina.llm.util.ResourceFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @description: 生成embedding工具
 * @author: IT006901
 * @date: 2025/12/10
 */
@Component
@Slf4j
public class GenerateEmbeddingTool {
    @Autowired
    private EmbeddingService embeddingService;

    @Value("${kb.raw-path}")
    private String rawPath;

    @Value("${kb.emb-path}")
    private String embPath;

    public Map<String, Object> generate() {
        File in = ResourceFileUtils.getResourceAsFile(rawPath);
        if (!in.exists()) {
            return Collections.singletonMap("error", "raw kb.xlsx not found: " + rawPath);
        }

        File out = ResourceFileUtils.getResourceAsFile(embPath);

        // overwrite header
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            bw.write("canonical_field,column_name,data_type,length,description,aliases,remark,priority_level,embedding\n");
        } catch (Exception e) {
            log.error("Failed to overwrite header", e);
        }

        int count = 0;

        // ---------------------
        // XLSX PARSE
        // ---------------------
        try (InputStream is = new FileInputStream(in);
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> it = sheet.iterator();
            if (it.hasNext()) {
                it.next(); // skip header row
            }

            while (it.hasNext()) {
                Row row = it.next();

                String canonical = cell(row, 0);
                String column = cell(row, 1);
                String dataType = cell(row, 2);
                String length = cell(row, 3);
                String description = cell(row, 4);
                String aliases = cell(row, 5);
                String remark = cell(row, 6);
                String priority = cell(row, 7);

                String inputText = buildEmbeddingInput(canonical, column, description, aliases, remark);
                float[] vec = embeddingService.embed(inputText);
                if (vec == null) {
                    return Collections.singletonMap("error", "embedding failed for " + canonical);
                }

                List<String> cols = Arrays.asList(canonical, column, dataType, length, description, aliases, remark, priority);
                CSVUtils.appendRowWithEmbedding(out, cols, vec);
                count++;

                Thread.sleep(50);
            }
        } catch (Exception e) {
            log.error("Failed to generate embeddings", e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("generated", count);

        return result;
    }

    private String cell(Row row, int index) {
        try {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                return "";
            }
            cell.setCellType(CellType.STRING);
            return cell.getStringCellValue().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildEmbeddingInput(String canonical, String column,
                                       String description, String aliases, String remark) {
        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isEmpty()) {
            sb.append(description).append("\n");
            sb.append(description).append("\n"); // repeat to add weight
        }
        if (aliases != null && !aliases.isEmpty()) {
            sb.append(aliases).append("\n");
        }
        if (canonical != null && !canonical.isEmpty()) {
            sb.append(canonical).append("\n");
        }
        if (column != null && !column.isEmpty()) {
            sb.append(column).append("\n");
        }
        if (remark != null && !remark.isEmpty()) {
            sb.append(remark).append("\n");
        }
        return sb.toString();
    }
}
