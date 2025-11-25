package com.example.rag.util;

import org.simmetrics.StringMetric;
import org.simmetrics.metrics.JaroWinkler;

public class SimilarityUtil {
    private static final StringMetric METRIC = new JaroWinkler();

    // normalize: trim + lower + remove underscores and spaces before calling outside
    public static double jaroWinkler(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        return METRIC.compare(s1, s2);
    }
}
