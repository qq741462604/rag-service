package com.example.rag.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.*;
import java.util.List;

public class CSVUtils {

    /**
     * append a row with embedding column as comma-separated floats (no brackets)
     */
    public static void appendRowWithEmbedding(File out, List<String> columns, float[] embedding) throws IOException {
        boolean exists = out.exists();
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out, true), "UTF-8"));
             CSVPrinter printer = new CSVPrinter(bw, CSVFormat.DEFAULT)) {
            // If file newly created, caller should write header separately.
            for (String c : columns) {
                printer.print(c == null ? "" : c);
            }
            // embedding column
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(Float.toString(embedding[i]));
            }
            printer.print(sb.toString());
            printer.println();
        }
    }
}
