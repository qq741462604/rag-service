//package com.example.rag.controller;
//
//import com.example.rag.model.RagResult;
//import com.example.rag.service.RagService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@RestController
//@RequestMapping("/rag")
//public class RagController {
//
//    @Autowired
//    private RagService ragService;
//
//    @PostMapping("/search")
//    public Map<String, Object> search(@RequestBody Map<String, String> body) {
//        String q = body.get("query");
//        RagResult result = ragService.search(q);
//
//        Map<String, Object> resp = new HashMap<String, Object>();
//        resp.put("canonicalField", result.getCanonicalField());
//        resp.put("confidence", result.getConfidence());
//        resp.put("source", result.getSource());
//        resp.put("matchedAlias", result.getMatchedAlias());
//        resp.put("description", result.getDescription());
//        resp.put("status", result.getStatus());
//        return resp;
//    }
//}
