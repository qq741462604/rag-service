package com.example.rag.controller;

import com.example.rag.service.EnhancedVectorSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/search")
public class SearchController {

    private final EnhancedVectorSearchService search;

    public SearchController(EnhancedVectorSearchService search) {
        this.search = search;
    }

    @GetMapping
    public List<EnhancedVectorSearchService.SearchResult> search(@RequestParam String q) {
        return search.hybridSearch(q, 5);
    }
}
