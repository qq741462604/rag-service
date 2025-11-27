package com.example.rag.service;

import java.util.concurrent.ConcurrentHashMap;

public class EmbeddingCache {

    private static final ConcurrentHashMap<String, float[]> cache = new ConcurrentHashMap<>();

    public static void put(String text, float[] vec) {
        cache.put(text, vec);
    }

    public static float[] get(String text) {
        return cache.get(text);
    }
}
