package com.example.rag.util;

import com.opencsv.CSVWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CSVUtils {

    /**
     * 覆盖写入 header（会覆盖同名文件）
     * headerItems 应该包含最后的 "embedding" 列名
     */
    public static void writeHeader(File out, List<String> headerItems) throws IOException {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out, false), StandardCharsets.UTF_8));
             CSVWriter csvWriter = new CSVWriter(w,
                     CSVWriter.DEFAULT_SEPARATOR,
                     CSVWriter.DEFAULT_QUOTE_CHARACTER,   // 使用默认的双引号，能正确包含含逗号的字段
                     CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                     CSVWriter.DEFAULT_LINE_END)) {

            String[] header = headerItems.toArray(new String[0]);
            // 强制把 header 字段按需要加引号（写 header 用 writeNext 默认行为即可）
            csvWriter.writeNext(header, false);
            csvWriter.flush();
        }
    }

    /**
     * 追加一行：columns + embedding 列（embedding 用逗号连接为一个字段）
     * 确保使用 CSVWriter.DEFAULT_QUOTE_CHARACTER，这样含逗号的字段会自动被双引号包起来。
     */
    public static void appendRowWithEmbedding(File out, List<String> columns, float[] embedding) throws IOException {
        // 如果文件不存在，会自动创建并追加
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out, true), StandardCharsets.UTF_8));
             CSVWriter csvWriter = new CSVWriter(writer,
                     CSVWriter.DEFAULT_SEPARATOR,
                     CSVWriter.DEFAULT_QUOTE_CHARACTER, // IMPORTANT: 使用双引号，避免逗号切分错位
                     CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                     CSVWriter.DEFAULT_LINE_END)) {

            // 拼 embedding 字符串（逗号分隔），但这是一个单独的字段，我们会由 CSVWriter 自动加引号
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(Float.toString(embedding[i]));
            }
            String embStr = sb.toString();

            // 构造一整行
            String[] row = new String[columns.size() + 1];
            for (int i = 0; i < columns.size(); i++) {
                row[i] = columns.get(i) == null ? "" : columns.get(i);
            }
            row[columns.size()] = embStr;

            // writeNext 第二个参数表示是否给所有字段加引号；false = 仅在必要时加引号（推荐）
            csvWriter.writeNext(row, false);
            csvWriter.flush();
        }
    }
}
