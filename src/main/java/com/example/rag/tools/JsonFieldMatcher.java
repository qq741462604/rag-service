package com.example.rag.tools;

import com.example.rag.model.FieldInfo;
import com.example.rag.service.CorrectionService;
import com.example.rag.service.EnhancedVectorSearchService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JsonFieldMatcher {

    private final CorrectionService correctionService;
    private final EnhancedVectorSearchService vectorService;

    public JsonFieldMatcher(
            CorrectionService correctionService,
            EnhancedVectorSearchService vectorService) {

        this.correctionService = correctionService;
        this.vectorService = vectorService;
    }

    public Map<String, FieldInfo> matchFields(List<Map<String, String>> items) {
        Map<String, FieldInfo> result = new LinkedHashMap<>();

        for (Map<String, String> item : items) {
            String name = item.getOrDefault("name", "");
            String desc = item.getOrDefault("description", "");

            FieldInfo matched = matchOne(desc, name);
            result.put(name, matched);
        }

        return result;
    }

    // description 优先
    private FieldInfo matchOne(String desc, String name) {
        FieldInfo best = null;
        float bestScore = -1;

        // ① description → correction
        FieldInfo byDescCorr = correctionService.correct(desc);
        if (byDescCorr != null) return byDescCorr;

        // ② description → alias
        FieldInfo byDescAlias = vectorService.searchByAlias(desc);
        if (byDescAlias != null) {
            best = byDescAlias;
            bestScore = 0.9f; // 高优先级
        }

        // ③ name → correction
        FieldInfo byNameCorr = correctionService.correct(name);
        if (byNameCorr != null) {
            return byNameCorr;
        }

        // ④ name → alias
        FieldInfo byNameAlias = vectorService.searchByAlias(name);
        if (byNameAlias != null && bestScore < 0.8f) { // 低优先级
            best = byNameAlias;
            bestScore = 0.8f;
        }

        // ⑤ description → vector（如果你后面加 query embedding）
        // 这里先留一个接口
        // FieldInfo byDescVector = vectorService.searchByVector(descVec);

        // ⑥ fallback
        return best;
    }
}