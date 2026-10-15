package com.cmbchina.llm.util;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;

import java.math.BigInteger;

public class WordStyleUtil {

    private WordStyleUtil() {
    }

    private static final String FONT_COLOR_0 = "000000";

    /**
     * 标题样式
     */
    public static void applyTitle(XWPFParagraph p) {
        p.setAlignment(ParagraphAlignment.CENTER);

        XWPFRun run = p.getRuns().get(0);
        run.setBold(true);
        run.setFontSize(16);
        run.setFontFamily("微软雅黑");
    }

    /**
     * 二级标题
     */
    public static void applySectionTitle(XWPFParagraph p) {
        XWPFRun run = p.getRuns().get(0);
        run.setBold(true);
        run.setFontSize(12);
        run.setFontFamily("微软雅黑");
    }

    /**
     * 表头单元格样式
     */
    public static void styleHeaderCell(XWPFTableCell cell) {
        setCellBackground(cell, "D9D9D9"); // 浅灰
        setCellCenter(cell);
    }

    /**
     * 普通单元格
     */
    public static void styleNormalCell(XWPFTableCell cell) {
        setCellCenter(cell);
    }

    /**
     * 设置背景色
     */
    private static void setCellBackground(XWPFTableCell cell, String color) {
        CTTcPr tcPr = cell.getCTTc().addNewTcPr();
        CTShd shd = tcPr.addNewShd();
        shd.setFill(color);
    }

    /* =========================================================
     * 红色警告单元格（needs_review）
     * ========================================================= */
    public static void setRedWarningCell(XWPFTableCell cell, String text) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);

        XWPFRun run = p.createRun();
        run.setText(text);
        run.setColor("FF0000");
        run.setBold(true);
    }


    /**
     * 单元格垂直居中
     */
    private static void setCellCenter(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().addNewTcPr();
        tcPr.addNewVAlign().setVal(STVerticalJc.CENTER);
    }

    /**
     * 表格边框
     */
    public static void setTableBorder(XWPFTable table) {
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, FONT_COLOR_0);
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, FONT_COLOR_0);
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, FONT_COLOR_0);
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, FONT_COLOR_0);
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, FONT_COLOR_0);
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 1, 0, FONT_COLOR_0);
    }

    /**
     * 设置列宽（单位：DXA）
     */
    public static void setTableColWidth(XWPFTable table, int[] widths) {
        for (int rowIndex = 0; rowIndex < table.getRows().size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            for (int i = 0; i < widths.length; i++) {
                XWPFTableCell cell = row.getCell(i);
                if (cell == null) {
                    continue;
                }

                CTTcPr tcPr = cell.getCTTc().addNewTcPr();
                CTTblWidth width = tcPr.addNewTcW();
                width.setType(STTblWidth.DXA);
                width.setW(BigInteger.valueOf(widths[i]));
            }
        }
    }
}