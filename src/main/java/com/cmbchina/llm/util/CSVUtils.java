package com.cmbchina.llm.util;

import com.opencsv.CSVWriter;

import java.io.*;
import java.util.List;

public class CSVUtils {

    /**
     * append a row with embedding column as comma-separated floats (no brackets)
     */
    public static void appendRowWithEmbedding(File out, List<String> columns, float[] embedding) throws IOException {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out, true), "UTF-8"));
             CSVWriter csvWriter = new CSVWriter(writer,
                     CSVWriter.DEFAULT_SEPARATOR,
                     CSVWriter.DEFAULT_QUOTE_CHARACTER,
                     CSVWriter.NO_ESCAPE_CHARACTER,
                     CSVWriter.DEFAULT_LINE_END)) {

            // 拼 embedding float[]
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(embedding[i]);
            }

            // 拼成一整行
            String[] row = new String[columns.size() + 1];
            for (int i = 0; i < columns.size(); i++) {
                row[i] = columns.get(i) == null ? "" : columns.get(i);
            }
            row[columns.size()] = sb.toString();

            csvWriter.writeNext(row, false);
        }
    }
}
