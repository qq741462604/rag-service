package com.example.rag.controller;

import com.example.rag.model.FieldInfo;
import com.example.rag.model.VectorSearchResult;
import com.example.rag.service.*;
import com.example.rag.service.VectorSearchService.Scored;
import com.example.rag.service.impl.EnhancedVectorSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/rag")
public class RagController {


    @Autowired
    private KbService kbService;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private VectorSearchService vectorSearch;

    @Autowired
    private AnswerService answerService;

    // retrieve-only: 返回 topK 带分数
    @GetMapping("/retrieve")
    public Map<String, Object> retrieve(@RequestParam("q") String q) {
        Map<String, Object> resp = new HashMap<String, Object>();
        if (q == null || q.trim().isEmpty()) {
            resp.put("found", false);
            resp.put("message", "empty query");
            return resp;
        }

        // exact alias
        Optional<String> alias = kbService.lookupAlias(q);
        if (alias.isPresent()) {
            FieldInfo f = kbService.get(alias.get());
            resp.put("found", true);
            resp.put("results", Collections.singletonList(f));
            return resp;
        }

        float[] qv = embeddingClient.embed(q);
        if (qv == null) {
            resp.put("found", false);
            resp.put("message", "embedding failed");
            return resp;
        }

        List<Scored<FieldInfo>> scored = vectorSearch.search(qv, kbService.all());
        List<Map<String,Object>> out = new ArrayList<Map<String,Object>>();
        for (Scored<FieldInfo> s : scored) {
            Map<String,Object> m = new HashMap<String,Object>();
            m.put("canonicalField", s.item.canonicalField);
            m.put("columnName", s.item.columnName);
            m.put("description", s.item.description);
            m.put("score", s.score);
            out.add(m);
        }
        resp.put("found", !out.isEmpty());
        resp.put("results", out);
        return resp;
    }

    // retrieve + answer: 检索并让模型生成（如企业不允许模型调用，这个接口可禁用）
    @GetMapping("/answer")
    public Map<String, Object> answer(@RequestParam("q") String q) {
        Map<String, Object> resp = new HashMap<String, Object>();
        float[] qv = embeddingClient.embed(q);
        if (qv == null) {
            resp.put("ok", false);
            resp.put("msg", "embedding failed");
            return resp;
        }
        List<Scored<FieldInfo>> scored = vectorSearch.search(qv, kbService.all());
        String ans = answerService.generateAnswer(q, scored);
        resp.put("ok", true);
        resp.put("answer", ans);
        resp.put("retrieved", scored.size());
        return resp;
    }

    @PostMapping("/admin/reload")
    public Map<String, Object> reload() throws Exception {
        Map<String, Object> r = new HashMap<String, Object>();
        kbService.load(System.getProperty("kb.load.path", "src/main/resources/data/kb_with_embedding.csv"));
        r.put("reloaded", true);
        return r;
    }
}
