package com.example.rag.util;

public class MathUtil {

    // 余弦相似度
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -2;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += ((double)a[i]) * ((double)b[i]);
            na += ((double)a[i]) * ((double)a[i]);
            nb += ((double)b[i]) * ((double)b[i]);
        }
        if (na == 0 || nb == 0) return -2;
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
    }
}
