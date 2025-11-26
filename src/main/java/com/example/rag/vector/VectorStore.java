package com.example.rag.vector;


import com.example.rag.model.FieldInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

@Component
public class VectorStore {

    private final Map<String, float[]> vectors = new HashMap<>();
    private int dimension = -1;

    @Value("${kb.load.path}")
    private String CSV_PATH;

    @PostConstruct
    public void load() throws Exception {
        System.out.println("[VectorStore] 加载向量文件: " + CSV_PATH);

        try (BufferedReader reader = new BufferedReader(new FileReader(CSV_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {

                String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;

                String key = parts[0];
                String[] nums = parts[1].split(",");

                // 第一次读 → 确定维度
                if (dimension < 0) {
                    dimension = nums.length;
                    System.out.println("[VectorStore] 自动识别向量维度: " + dimension);
                }

                float[] vector = new float[dimension];
                for (int i = 0; i < dimension; i++) {
                    vector[i] = Float.parseFloat(nums[i]);
                }

                vectors.put(key, normalize(vector));
            }
        }

        System.out.println("[VectorStore] 加载完成，共 " + vectors.size() + " 条数据");
    }

    public int getDimension() {
        return dimension;
    }

    public Map<String, float[]> getVectors() {
        return vectors;
    }

    /** L2 归一化 */
    private float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        float norm = (float) Math.sqrt(sum);

        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
        return out;
    }
}
