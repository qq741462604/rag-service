package com.cmbchina.llm.util;

import com.cmbchina.llm.entity.model.DocContentDTO;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.util.List;

/**
 * 通用字段表构建器
 *
 * 适用于：请求字段 / 响应字段
 */
public class FieldTableBuilder {

    private static final String[] HEADERS =
            {"字段名", "描述", "类型", "长度", "必填", "备注"};

    private static final int[] COL_WIDTH =
            {2000, 3500, 1500, 1000, 1000, 3000};

    public static void build(
            XWPFDocument doc,
            List<DocContentDTO.FieldDTO> fields
    ) {
        XWPFTable table = doc.createTable(fields.size() + 1, HEADERS.length);
        WordStyleUtil.setTableBorder(table);

        // ===== 表头 =====
        XWPFTableRow headerRow = table.getRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            XWPFTableCell cell = headerRow.getCell(i);
            cell.setText(HEADERS[i]);
            WordStyleUtil.styleHeaderCell(cell);
        }

        // ===== 表体 =====
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
                setRedWarningCell(remarkCell);
            } else {
                remarkCell.setText(f.getRemarks());
            }

            for (XWPFTableCell cell : row.getTableCells()) {
                WordStyleUtil.styleNormalCell(cell);
            }
        }

        WordStyleUtil.setTableColWidth(table, COL_WIDTH);
    }

    /** 红色 needs_review 单元格 */
    private static void setRedWarningCell(XWPFTableCell cell) {
        cell.removeParagraph(0);

        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);

        XWPFRun run = p.createRun();
        run.setText("⚠ 请人工确认");
        run.setColor("FF0000");
        run.setBold(true);
    }
}
