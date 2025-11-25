package com.example.rag.tools;

import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;

public class GenerateEmbedding {

    // 你的 KB 路径（来源 CSV）
    private static final String INPUT_CSV = "src/main/resources/data/kb.csv";
    // 生成后的 CSV（带 embedding）
    private static final String OUTPUT_CSV = "src/main/resources/data/kb_with_embedding.csv";

    // 替换为你的阿里 DashScope API Key
    private static final String API_KEY = "your_api_key_here";

    public static void main(String[] args) throws Exception {

        CSVReader reader = new CSVReader(new FileReader(INPUT_CSV));
        CSVWriter writer = new CSVWriter(new FileWriter(OUTPUT_CSV));

        String[] header = reader.readNext();
        if (header == null) return;

        // 新增一列 embedding
        String[] newHeader = Arrays.copyOf(header, header.length + 1);
        newHeader[newHeader.length - 1] = "embedding";
        writer.writeNext(newHeader);

        String[] row;

        while ((row = reader.readNext()) != null) {
            // 拼接字段内容（可自行调整）
            String text = row[0] + " " + row[1] + " " + row[2] + " " + row[3];

            float[] vector = generateEmbedding(text);

            String embeddingString = floatArrayToString(vector);

            String[] newRow = Arrays.copyOf(row, row.length + 1);
            newRow[newRow.length - 1] = embeddingString;

            writer.writeNext(newRow);
        }

        writer.close();
        reader.close();

        System.out.println("🔥 KB embedding 生成完成，输出文件：" + OUTPUT_CSV);
    }

    private static float[] generateEmbedding(String text) {
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(API_KEY)
                .model("text-embedding-v2")
                .input(text)
                .build();

        TextEmbedding.Result result = TextEmbedding.call(param);
        return result.getOutput().getEmbeddings().get(0).getEmbedding();
    }

    private static String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            sb.append(arr[i]);
            if (i < arr.length - 1) sb.append(",");
        }
        return sb.toString();
    }
}
