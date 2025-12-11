package com.example.rag.util;

import java.util.HashMap;
import java.util.Map;

public class CsvKeyCleaner {

    private CsvKeyCleaner() {}
    /**
     * 清洗 CSV 的 key（列名）
     * - 去掉 BOM (\uFEFF)
     * - 去掉前后空格
     * - 去掉控制字符
     * - 统一小写（可选）
     */
    public static String cleanKey(String key) {
        if (key == null) return null;

        return key
                .replace("\uFEFF", "")          // 移除 UTF-8 BOM
                .replaceAll("\\p{Cntrl}", "")   // 删除控制字符
                .trim()
                .toLowerCase();                 // 可按需求决定是否 toLowerCase
    }

    /**
     * 输入原始 map（从 CSV 读取的），输出 key-clean 后的 map
     */
    public static Map<String, String> cleanKeys(Map<String, String> raw) {
        Map<String, String> cleaned = new HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String cleanKey = cleanKey(entry.getKey());
            cleaned.put(cleanKey, entry.getValue());
        }
        return cleaned;
    }
}
