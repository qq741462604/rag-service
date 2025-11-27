package com.example.rag.model;


import java.util.*;
import java.util.stream.Collectors;

public class SearchResult {

    public static class Item {
        public FieldInfo field;
        public double score;
        public String source; // "vector" / "bm25" / "fuzzy"
    }

    private final List<Item> items = new ArrayList<>();

    public void add(FieldInfo field, double score, String source) {
        Item item = new Item();
        item.field = field;
        item.score = score;
        item.source = source;
        items.add(item);
    }

    public List<Item> getItems() {
        return items;
    }

    /**
     * 将多个召回结果融合，score 可加权
     * @param results 多个结果
     * @param topK 取 K 条
     */
    public static SearchResult mergeWeighted(int topK, Map<String, Double> weights, SearchResult... results) {

        // 1) 按 canonicalField 去重
        Map<String, Item> best = new HashMap<>();

        for (SearchResult r : results) {
            if (r == null) continue;

            for (Item item : r.items) {
                double w = weights.getOrDefault(item.source, 1.0);
                double finalScore = item.score * w;

                String key = item.field.canonicalField;

                // 选最高得分
                if (!best.containsKey(key) || best.get(key).score < finalScore) {
                    Item newItem = new Item();
                    newItem.field = item.field;
                    newItem.source = item.source;
                    newItem.score = finalScore;
                    best.put(key, newItem);
                }
            }
        }

        // 2) 排序
        List<Item> sorted = best.values().stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .collect(Collectors.toList());

        // 3) 输出
        SearchResult finalResult = new SearchResult();
        finalResult.items.addAll(sorted);
        return finalResult;
    }
}
