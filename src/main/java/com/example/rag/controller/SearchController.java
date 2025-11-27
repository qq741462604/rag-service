package com.example.rag.controller;

import com.example.rag.service.EnhancedVectorSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/search")
public class SearchController {

    private final EnhancedVectorSearchService search;

    public SearchController(EnhancedVectorSearchService search) {
        this.search = search;
    }

    /**
     * 不含纠错
     * @param q
     * @return
     */
    @GetMapping
    public List<EnhancedVectorSearchService.SearchResult> search(@RequestParam String q) {
        return search.hybridSearch(q, 5);
    }

    /**
     * 含纠错
     * @param q
     * @return
     */
    @GetMapping("/match")
    public List<EnhancedVectorSearchService.Result> match(@RequestParam String q) {
//        return search.hybridSearch(q, 5);
        return search.match(q, 3);
    }

    @PostMapping("/match-json")
    public Object matchJson(@RequestBody List<Map<String, String>> items) {
        return jsonFieldMatcher.matchFields(items);
    }

}
