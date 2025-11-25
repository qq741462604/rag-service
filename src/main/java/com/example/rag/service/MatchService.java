package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import org.springframework.stereotype.Service;

@Service
public class MatchService {

    private final KbService kb;

    public MatchService(KbService kb) {
        this.kb = kb;
    }

    public FieldInfo match(String queryField) {

        // ① alias 精确命中
        return kb.lookup(queryField)
                .map(kb::getInfo)
                .orElseGet(() -> fuzzyMatch(queryField));
    }

    private FieldInfo fuzzyMatch(String q) {
        int best = -1;
        FieldInfo bestF = null;

        for (FieldInfo f : kb.all()) {
            int score = similarity(q, f.aliases + "," + f.canonicalField + "," + f.columnName);

            // 允许使用 priority 加权
            score += f.priorityLevel * 3;

            if (score > best) {
                best = score;
                bestF = f;
            }
        }

        // 阈值小于 20 认为不可靠
        if (best < 20) return null;

        return bestF;
    }

    // 最简单粗暴的字符交集相似度（无需库）优化
    private int similarity(String a, String b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        int count = 0;
        for (char c : a.toCharArray()) {
            if (b.indexOf(c) >= 0) count++;
        }
        return count * 10;
    }
}
