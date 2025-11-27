package com.example.rag.core;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 处理用户输入（JSON 数组）并生成最终查询字符串
 */
public class FieldQueryService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WeightedQueryBuilder weightedQueryBuilder = new WeightedQueryBuilder();

    /**
     * 输入：JSON 数组字符串
     * 输出：加权后的搜索 query
     */
    public String buildQueryFromJson(String jsonArray) throws Exception {
        List<Map<String, Object>> list = mapper.readValue(
                jsonArray,
                new TypeReference<List<Map<String, Object>>>() {}
        );

        return weightedQueryBuilder.buildWeightedQueryForList(list);
    }
}

