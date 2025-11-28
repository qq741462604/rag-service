package com.example.rag.util;

public class VectorUtils {

    public static float[] normalize(float[] v) {
        if (v == null) return null;
        double s = 0;
        for (float x : v) s += x * x;
        s = Math.sqrt(s);
        if (s == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / s);
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null) return 0.0;
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
    }
}
