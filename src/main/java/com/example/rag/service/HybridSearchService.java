package com.example.rag.service;

import com.example.rag.core.FieldQueryService;
import com.example.rag.model.FieldInfo;
import com.example.rag.model.VectorSearchResult;
import com.example.rag.service.BM25Service;
import com.example.rag.service.CorrectionService;
import com.example.rag.service.EnhancedVectorSearchService;
import com.example.rag.service.KbService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HybridSearchService {
    private final FieldQueryService fieldQueryService = new FieldQueryService();
    private final VectorSearchService vectorSearchService;
    private final BM25Service bm25Service;
    private final FuzzyService fuzzyService;

    public HybridSearchService(
            VectorSearchService vectorSearchService,
            BM25Service bm25Service,
            FuzzyService fuzzyService
    ) {
        this.vectorSearchService = vectorSearchService;
        this.bm25Service = bm25Service;
        this.fuzzyService = fuzzyService;
    }

    /**
     * 输入是 JSON 数组 → 自动加权 → 搜索
     */
    public SearchResult searchFieldJson(String jsonArray) throws Exception {

        // 1) 将 JSON 数组转成 description 优先的 weighted query
        String finalQuery = fieldQueryService.buildQueryFromJson(jsonArray);

        // 2) 你的原向量搜索 / BM25 搜索 / fuzzy
        SearchResult v = vectorSearchService.search(finalQuery);
        SearchResult b = bm25Service.search(finalQuery);
        SearchResult f = fuzzyService.search(finalQuery);

        // 3) 拼融合结果
        return SearchResult.merge(v, b, f);
    }


    // ===================== 内部结构体 ======================
    public static class SearchResult {
        public String query;
        public String source; // correction / canonical / hybrid
        public List<Candidate> results;
    }

    public static class Candidate {
        public String canonicalField;
        public String columnName;
        public String description;
        public int priorityLevel;
        public float score;

    }
}
