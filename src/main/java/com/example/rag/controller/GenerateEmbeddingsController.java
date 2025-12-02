package com.example.rag.controller;

import com.example.rag.service.EmbeddingService;
import com.example.rag.util.CSVUtils;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.apache.poi.ss.usermodel.*;
import java.io.*;
import java.util.*;

/**
 * Read raw kb.csv -> call embedding service -> produce kb_with_embedding.csv
 */
@RestController
public class GenerateEmbeddingsController {

    @Autowired
    private EmbeddingService embeddingService;

    @Value("${kb.raw-path}")
    private String rawPath;

    @Value("${kb.emb-path}")
    private String embPath;

    @PostMapping("/admin/generate-embeddings")
    public Map<String,Object> generate() throws Exception {
        File in = new File(rawPath);
        if (!in.exists()) return Collections.singletonMap("error", "raw kb.csv not found: " + rawPath);
        File out = new File(embPath);
        // overwrite header
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), "UTF-8"))) {
            bw.write("canonical_field,column_name,data_type,length,description,aliases,remark,priority_level,embedding\n");
        }

        int count = 0;
        try (Reader r = new FileReader(in);
             CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(r)) {

            for (CSVRecord rec : parser) {
                String canonical = safe(rec, 0);
                String column = safe(rec, 1);
                String dataType = safe(rec,2);
                String length = safe(rec,3);
                String description = safe(rec,4);
                String aliases = safe(rec,5);
                String remark = safe(rec,6);
                String priority = safe(rec,7);

                String inputText = buildEmbeddingInput(canonical, column, description, aliases, remark);
                float[] vec = embeddingService.embed(inputText);
                if (vec == null) {
                    return Collections.singletonMap("error", "embedding failed for " + canonical);
                }
                // write row
                List<String> cols = Arrays.asList(canonical, column, dataType, length, description, aliases, remark, priority);
                CSVUtils.appendRowWithEmbedding(out, cols, vec);
                count++;
                // avoid rate issues: small sleep (optional)
                Thread.sleep(50);
            }
        }
        Map<String,Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("generated", count);

        return result;
    }

    @PostMapping("/admin/generate-xlsx-embeddings")
    public Map<String,Object> generateByExcel() throws Exception {
        File in = new File(rawPath);
        if (!in.exists()) return Collections.singletonMap("error", "raw kb.xlsx not found: " + rawPath);

        File out = new File(embPath);

        // overwrite header
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), "UTF-8"))) {
            bw.write("canonical_field,column_name,data_type,length,description,aliases,remark,priority_level,embedding\n");
        }

        int count = 0;

        // ---------------------
        // XLSX PARSE
        // ---------------------
        try (InputStream is = new FileInputStream(in);
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> it = sheet.iterator();
            if (it.hasNext()) it.next(); // skip header row

            while (it.hasNext()) {
                Row row = it.next();

                String canonical = cell(row, 0);
                String column    = cell(row, 1);
                String dataType  = cell(row, 2);
                String length    = cell(row, 3);
                String description = cell(row, 4);
                String aliases   = cell(row, 5);
                String remark    = cell(row, 6);
                String priority  = cell(row, 7);

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
        }

        Map<String,Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("generated", count);

        return result;
    }

    private String cell(Row row, int index) {
        try {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) return "";
            cell.setCellType(CellType.STRING);
            return cell.getStringCellValue().trim();
        } catch (Exception e) {
            return "";
        }
    }
    private String safe(CSVRecord r, int idx) {
        try { return r.get(idx); } catch (Exception e) { return ""; }
    }

    private String buildEmbeddingInput(String canonical, String column, String description, String aliases, String remark) {
        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isEmpty()) {
            sb.append(description).append("\n");
            // repeat description to increase weight
            sb.append(description).append("\n");
        }
        if (aliases != null && !aliases.isEmpty()) sb.append(aliases).append("\n");
        if (canonical != null && !canonical.isEmpty()) sb.append(canonical).append("\n");
        if (column != null && !column.isEmpty()) sb.append(column).append("\n");
        if (remark != null && !remark.isEmpty()) sb.append(remark).append("\n");
        return sb.toString();
    }
}
