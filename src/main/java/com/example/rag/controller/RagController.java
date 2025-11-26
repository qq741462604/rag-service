package com.example.rag.controller;

import com.example.rag.model.FieldInfo;
import com.example.rag.service.MatchService;
import com.example.rag.service.KbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagController {

    @Autowired
    private MatchService matchService;

    @Autowired
    private KbService kbService;

    @GetMapping("/match")
    public Map<String, Object> match(@RequestParam("q") String q) {
        Map<String, Object> resp = new HashMap<String, Object>();
        FieldInfo f = matchService.match(q);
        if (f == null) {
            resp.put("found", false);
            resp.put("message", "NOT_FOUND");
            return resp;
        }
        resp.put("found", true);
        resp.put("canonicalField", f.canonicalField);
        resp.put("columnName", f.columnName);
        resp.put("description", f.description);
        resp.put("aliases", f.aliases);
        return resp;
    }

    // admin reload endpoint
    @PostMapping("/admin/reload")
    public Map<String, Object> reload() throws Exception {
        Map<String, Object> r = new HashMap<String, Object>();
        kbService.load(System.getProperty("kb.load.path", "src/main/resources/data/kb_with_embedding.csv"));
        r.put("reloaded", true);
        return r;
    }
}
