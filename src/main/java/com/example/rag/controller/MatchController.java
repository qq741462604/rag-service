//package com.example.rag.controller;
//
//import com.example.rag.service.EnhancedVectorSearchService;
//import com.example.rag.service.EnhancedVectorSearchService.MatchResult;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//public class MatchController {
//
//    private final EnhancedVectorSearchService service;
//
//    public MatchController(EnhancedVectorSearchService service) {
//        this.service = service;
//    }
//
//    @GetMapping("/api/match")
//    public MatchResult match(
//            @RequestParam("q") String q,
//            @RequestParam(value = "k", defaultValue = "5") int k
//    ) {
//        return service.match(q, k);
//    }
//}
