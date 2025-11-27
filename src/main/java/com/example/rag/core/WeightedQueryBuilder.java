package com.example.rag.core;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 构建加权查询字符串，原则：description > name
 */
public class WeightedQueryBuilder {

    /** description 权重 */
    private final int descWeight = 3;

    /** name 权重 */
    private final int nameWeight = 1;

    /**
     * 单条字段的 query 构建
     */
    public String buildWeightedQuery(Map<String, Object> item) {
        String name = (String) item.getOrDefault("name", "");
        String desc = (String) item.getOrDefault("description", "");

        StringBuilder sb = new StringBuilder();

        // description 权重更高
        for (int i = 0; i < descWeight; i++) {
            sb.append(desc).append(" ");
        }

        // name 权重较低
        for (int i = 0; i < nameWeight; i++) {
            sb.append(name).append(" ");
        }

        return sb.toString().trim();
    }

    /**
     * 一组 JSON 字段列表合并为一个 query
     */
    public String buildWeightedQueryForList(List<Map<String, Object>> list) {
        return list.stream()
                .map(this::buildWeightedQuery)
                .collect(Collectors.joining(" "));
    }
}
