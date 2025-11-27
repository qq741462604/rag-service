package com.example.rag.controller;

import com.example.rag.service.CorrectionService;
import com.example.rag.service.KbService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理接口：提交纠错、手动 reload KB
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final CorrectionService correctionService;
    private final KbService kbService;

    public AdminController(CorrectionService correctionService, KbService kbService) {
        this.correctionService = correctionService;
        this.kbService = kbService;
    }

    @PostMapping("/correct")
    public Map<String, Object> correct(@RequestParam String query, @RequestParam String wrong, @RequestParam String correct) {
        boolean ok = correctionService.record(query, wrong, correct);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", ok);
        if (ok) r.put("message", "recorded");
        else r.put("message", "failed");
        return r;
    }

    @PostMapping("/reload")
    public Map<String, Object> reloadKb() {
        kbService.reload();
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("message", "kb reloaded");
        return r;
    }
}
