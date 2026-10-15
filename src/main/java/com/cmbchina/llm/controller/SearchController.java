package com.cmbchina.llm.controller;

import com.cmbchina.llm.service.CorrectionService;
import com.cmbchina.llm.service.EnhancedVectorSearchService.Hit;
import com.cmbchina.llm.service.JsonFieldMatcher;
import com.cmbchina.llm.service.KbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SearchController - 提供三个接口：
 * - POST /search-json  (body: JSON array of {name, description})
 * - POST /admin/generate-embeddings  (trigger generation)
 * - POST /admin/correct?query=...&canonical=...
 */
@RestController
public class SearchController {

    private final JsonFieldMatcher matcher;
    private final CorrectionService correction;

    public SearchController(JsonFieldMatcher matcher,
                            CorrectionService correction) {
        this.matcher = matcher;
        this.correction = correction;
    }

    @Autowired
    private KbService kbService;

    @PostMapping("/search-json")
    public List<Hit> searchJson(@RequestBody List<Map<String, String>> items) {
        return matcher.match(items);
    }


    @PostMapping("/admin/correct")
    public Map<String, Object> correct(@RequestParam String query, @RequestParam String canonical) {
        boolean ok = correction.recordCorrection(query, canonical);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", ok);
        return map;
    }

    @PostMapping("/admin/reload-kb")
    public Map<String, Object> reloadKb() {
        Map<String, Object> map = new HashMap<>();
        try {
            kbService.reload();
            map.put("ok", true);
        } catch (Exception e) {
            map.put("ok", false);
            map.put("error", e.getMessage());
        }

        return map;
    }
}
