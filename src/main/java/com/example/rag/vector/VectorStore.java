//package com.example.rag.vector;
//
//import org.springframework.stereotype.Component;
//
//import javax.annotation.PostConstruct;
//import java.io.*;
//import java.util.*;
//
//@Component
//public class VectorStore {
//
//    private final Map<String, float[]> vectors = new HashMap<>();
//    private int dimension = -1;
//
//    private static final String VECTOR_FILE =
//            System.getProperty("kb.vector", "src/main/resources/kb_vector.csv");
//
//    @PostConstruct
//    public void load() throws Exception {
//        File file = new File(VECTOR_FILE);
//        if (!file.exists()) {
//            System.out.println("[VectorStore] 没有向量文件，将在第一次查询时提示缺失");
//            return;
//        }
//
//        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
//            String line;
//
//            while ((line = reader.readLine()) != null) {
//                String[] parts = line.split(",", 2);
//                String key = parts[0];
//                String[] nums = parts[1].split(",");
//
//                if (dimension < 0) dimension = nums.length;
//
//                float[] v = new float[dimension];
//                for (int i = 0; i < dimension; i++) {
//                    v[i] = Float.parseFloat(nums[i]);
//                }
//                vectors.put(key, normalize(v));
//            }
//        }
//
//        System.out.println("[VectorStore] 加载向量：" + vectors.size() + " 条，维度=" + dimension);
//    }
//
//    public int getDimension() {
//        return dimension;
//    }
//
//    public boolean contains(String key) {
//        return vectors.containsKey(key);
//    }
//
//    public void put(String key, float[] v) {
//        vectors.put(key, normalize(v));
//    }
//
//    public Map<String, float[]> all() {
//        return vectors;
//    }
//
//    private float[] normalize(float[] v) {
//        double sum = 0;
//        for (float x : v) sum += x * x;
//        float norm = (float) Math.sqrt(sum);
//
//        float[] out = new float[v.length];
//        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
//        return out;
//    }
//}
