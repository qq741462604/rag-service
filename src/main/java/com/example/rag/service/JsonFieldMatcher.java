package com.example.rag.service;

import com.example.rag.service.EnhancedVectorSearchService.Hit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * JsonFieldMatcher - 适配器：接收 JSON 数组（已被 Jackson 转为 List<Map>）
 */
@Service
public class JsonFieldMatcher {

    private final EnhancedVectorSearchService enhanced;

    public JsonFieldMatcher(EnhancedVectorSearchService enhanced) {
        this.enhanced = enhanced;
    }

    public List<Hit> match(List<Map<String, String>> items) {
        return enhanced.matchJsonFields(items, 5);
    }
}
