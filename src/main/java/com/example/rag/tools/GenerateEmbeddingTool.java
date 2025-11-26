package com.example.rag.tools;

import com.alibaba.fastjson.JSON;
import com.example.rag.embedding.EmbeddingService;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class GenerateEmbeddingTool {

    public static void main(String[] args) throws Exception {

        String input = "src/main/resources/kb.csv";
        String output = "src/main/resources/kb_embedding.csv";

        EmbeddingService service = new EmbeddingService();

        try (
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(input), StandardCharsets.UTF_8));
                BufferedWriter bw = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8))
        ) {
            String header = br.readLine();
            bw.write(header + ",embedding\n");

            String line;

            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",");
                String content = cols[3];  // description

                float[] vec = service.embed(content);

                String json = JSON.toJSONString(vec);

                bw.write(line + ",\"" + json + "\"\n");
            }
        }

        System.out.println("Embedding 生成完毕！");
    }
}
