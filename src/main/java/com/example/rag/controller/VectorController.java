package com.example.rag.controller;

import com.example.rag.service.EmbeddingClient;
import com.example.rag.service.impl.EnhancedVectorSearchService;
import com.example.rag.vector.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/vector")
public class VectorController {

    private final EnhancedVectorSearchService searchService;
    private final VectorStore vectorStore;

    public VectorController(EnhancedVectorSearchService searchService,
                            VectorStore vectorStore) {
        this.searchService = searchService;
        this.vectorStore = vectorStore;
    }


    @Autowired
    private EmbeddingClient embeddingClient;

    @PostMapping("/search")
    public Object search(@RequestBody QueryReq req) {

        float[] qv = embeddingClient.embed(req.query);

        List<EnhancedVectorSearchService.Result> result =
                searchService.topK(qv, req.k);

        Map<String, Object> map = new HashMap<>();
        map.put("dimension", vectorStore.getDimension());
        map.put("queryNorm", req.vector.length);
        map.put("topK", result);
        return map;
    }

    public static class QueryReq {
        public float[] vector;
        public int k = 3;
        public String  query;
    }
}
