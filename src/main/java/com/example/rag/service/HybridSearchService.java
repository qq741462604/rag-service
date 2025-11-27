package com.example.rag.service;

import com.example.rag.core.FieldQueryService;
import com.example.rag.model.FieldInfo;
import com.example.rag.model.SearchResult;
import com.example.rag.model.VectorSearchResult;
import com.example.rag.service.BM25Service;
import com.example.rag.service.CorrectionService;
import com.example.rag.service.EnhancedVectorSearchService;
import com.example.rag.service.KbService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

//@Service
public class HybridSearchService {
    private final FieldQueryService fieldQueryService = new FieldQueryService();

    private final SearchEngine vectorSearch;
    private final SearchEngine bm25Search;
    private final SearchEngine fuzzySearch;

    private final CorrectionService correctionService; // 可选

    private final Map<String, Double> weights = new HashMap<>(3);
    /** 默认权重（你可调） */
//    private final Map<String, Double> weights = Map.of(
//            "vector", 1.0,
//            "bm25", 0.8,
//            "fuzzy", 0.3
//    );

    public HybridSearchService(
            SearchEngine vectorSearch,
            SearchEngine bm25Search,
            SearchEngine fuzzySearch,
            CorrectionService correctionService
    ) {
        this.vectorSearch = vectorSearch;
        this.bm25Search = bm25Search;
        this.fuzzySearch = fuzzySearch;
        this.correctionService = correctionService;
        weights.put("vector", 1.0);
        weights.put("bm25", 0.8);
        weights.put("fuzzy", 0.3);
    }

    /**
     * 输入：JSON 数组 → 输出：融合搜索结果
     */
    public SearchResult searchJson(String jsonArray) throws Exception {

        // (1) JSON -> Weighted Query
        String finalQuery = fieldQueryService.buildQueryFromJson(jsonArray);

        // (2) 纠错（可选）
        String correctedQuery = correctionService != null
                ? correctionService.applyCorrection(finalQuery)
                : finalQuery;

        // (3) 多源召回
        SearchResult vector = vectorSearch.search(correctedQuery);
        SearchResult bm25 = bm25Search.search(correctedQuery);
        SearchResult fuzzy = fuzzySearch.search(correctedQuery);

        // (4) 融合
        return SearchResult.mergeWeighted(
                10,        // topK = 10
                weights,
                vector, bm25, fuzzy
        );
    }
}
