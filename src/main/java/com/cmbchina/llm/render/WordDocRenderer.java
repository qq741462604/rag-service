package com.cmbchina.llm.render;

import com.cmbchina.llm.entity.model.DocContentDTO;
import com.cmbchina.llm.util.FieldTableBuilder;
import com.cmbchina.llm.util.WordStyleUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Word 文档渲染器（完整版）
 * <p>
 * Java 8 + Apache POI
 * 结构与 Markdown 输出保持一致
 */
public class WordDocRenderer implements DocRenderer {
    /**
     * 后面复用模板再启用 return new WordDocRenderer(Paths.get("docs/api-doc-template.docx"));
     * private Path templatePath;
     *
     * public WordDocRenderer(Path templatePath) {
     * this.templatePath = templatePath;
     * }
     */
    @Override
    public File render(DocContentDTO dto, Path outputDir) throws Exception {

        File file = outputDir.resolve(dto.getApiTitle() + ".docx").toFile();
        XWPFDocument doc = new XWPFDocument();

        /* ================= 一、标题 ================= */
        XWPFParagraph title = doc.createParagraph();
        title.createRun().setText(dto.getApiTitle());
        WordStyleUtil.applyTitle(title);

        /* ================= 二、请求结构定义 ================= */
        sectionTitle(doc, "一、请求结构定义");

        FieldTableBuilder.build(doc, dto.getRequestFields());

        /* ================= 三、请求报文示例 ================= */
        sectionTitle(doc, "二、请求报文示例");
        createJsonBlock(doc, dto.getRequestExample());

        /* ================= 四、应答结构定义 ================= */
        sectionTitle(doc, "三、应答结构定义");

        if (dto.getResponseFields() == null || dto.getResponseFields().isEmpty()) {
            createTextLine(doc, "（待补充）");
        } else {
            FieldTableBuilder.build(doc, dto.getResponseFields());
        }

        /* ================= 五、应答报文示例 ================= */
        sectionTitle(doc, "四、应答报文示例");
        createJsonBlock(doc, dto.getResponseExample());

        /* ================= 六、事件源创建 API ================= */
        sectionTitle(doc, "五、事件源创建 API");
        createJsonBlock(doc, dto.getEventPost());

        /* ================= 七、维度映射创建 API ================= */
        sectionTitle(doc, "六、维度映射创建 API");
        createJsonBlock(doc, dto.getEventMappingsPost());

        /* ================= 八、建表语句 ================= */
        sectionTitle(doc, "七、建表语句");
        createSqlBlock(doc, dto.getSql());

        try (FileOutputStream fos = new FileOutputStream(file)) {
            doc.write(fos);
        }
        return file;
    }

/* =========================================================
 * 字段表（请求 / 响应通用）
 * ========================================================= */
private void createFieldTable(XWPFDocument doc, List<DocContentDTO.FieldDTO> fields) {

    XWPFTable table = doc.createTable(fields.size() + 1, 6);
    WordStyleUtil.setTableBorder(table);

    String[] headers = {"字段名", "描述", "类型", "长度", "必填", "备注"};
    XWPFTableRow headerRow = table.getRow(0);

    for (int i = 0; i < headers.length; i++) {
        XWPFTableCell cell = headerRow.getCell(i);
        cell.setText(headers[i]);
        WordStyleUtil.styleHeaderCell(cell);
    }

    for (int i = 0; i < fields.size(); i++) {
        DocContentDTO.FieldDTO f = fields.get(i);
        XWPFTableRow row = table.getRow(i + 1);

        row.getCell(0).setText(f.getName());
        row.getCell(1).setText(f.getDescription());
        row.getCell(2).setText(f.getType());
        row.getCell(3).setText(f.getLength());
        row.getCell(4).setText(f.isRequired() ? "是" : "否");

        XWPFTableCell remarkCell = row.getCell(5);
        if (f.isNeedsReview()) {
            WordStyleUtil.setRedWarningCell(remarkCell, "⚠ 请人工确认");
        } else {
            remarkCell.setText(f.getRemarks());
        }

        for (XWPFTableCell cell : row.getTableCells()) {
            WordStyleUtil.styleNormalCell(cell);
        }
    }

    WordStyleUtil.setTableColWidth(
            table,
            new int[]{2000, 3500, 1500, 1000, 1000, 3000}
    );
}

    /* =========================================================
     * JSON 示例块（请求 / 响应 / 事件 / 映射）
     * ========================================================= */
    private void createJsonBlock(XWPFDocument doc, Object obj) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(prettyJson(obj));
        applyCodeBlockStyle(p);
    }

    /* =========================================================
     * SQL 块
     * ========================================================= */
    private void createSqlBlock(XWPFDocument doc, String sql) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(sql == null ? "" : sql);
        applyCodeBlockStyle(p);
    }
    /* =========================================================
     * 二级标题
     * ========================================================= */
    private void sectionTitle(XWPFDocument doc, String title) {
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(title);
        WordStyleUtil.applySectionTitle(p);
    }

    /* =========================================================
     * 普通文本行
     * ========================================================= */
    private void createTextLine(XWPFDocument doc, String text) {
        doc.createParagraph().createRun().setText(text);
    }


    /* =========================================================
     * JSON Pretty
     * ========================================================= */
    private String prettyJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /* =========================================================
     * 代码块样式（灰底 + 等宽）
     * ========================================================= */
    private void applyCodeBlockStyle(XWPFParagraph p) {
        XWPFRun run = p.getRuns().get(0);
        run.setFontFamily("Consolas");
        run.setFontSize(10);

        p.setSpacingBefore(200);
        p.setSpacingAfter(200);

        p.getCTP().addNewPPr().addNewShd().setFill("F2F2F2");
    }
}
