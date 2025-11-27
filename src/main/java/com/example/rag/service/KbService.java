package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KbService {

    private static final String KB_FILE = "src/main/resources/data/kb_with_embedding.csv";

    // 主存储
    private final Map<String, FieldInfo> kb = new ConcurrentHashMap<>();

    // canonicalField 快速索引
    private final Map<String, FieldInfo> canonicalIndex = new ConcurrentHashMap<>();

    // alias 倒排索引 (alias -> list<FieldInfo>)
    private final Map<String, List<FieldInfo>> aliasIndex = new ConcurrentHashMap<>();

    @Getter
    private long lastLoadTime = 0;

    private final ThreadPoolTaskExecutor executor;
    private final BM25Service bm25Service;

    public KbService(BM25Service bm25Service) {
        this.bm25Service = bm25Service;

        // 异步线程池
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("kb-loader-");
        executor.initialize();
    }

    /** ----------- 初始化加载 KB ----------- **/
    @PostConstruct
    public void init() {
        loadKbAsync();   // 启动时异步加载
        watchKbFile();   // 文件监控，热更新
    }

    /** 外部调用 reload */
    public void reload() {
        loadKbAsync();
    }

    /** 异步加载 KB */
    private void loadKbAsync() {
        executor.submit(this::loadKb);
    }

    /** ----------- 实际加载逻辑 ----------- **/
    private synchronized void loadKb() {
        log.info("Loading KB from {}", KB_FILE);

        File file = new File(KB_FILE);
        if (!file.exists()) {
            log.error("KB not found: {}", KB_FILE);
            return;
        }

        Map<String, FieldInfo> newKb = new ConcurrentHashMap<>();
        Map<String, FieldInfo> newCanonicalIndex = new ConcurrentHashMap<>();
        Map<String, List<FieldInfo>> newAliasIndex = new ConcurrentHashMap<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String header = br.readLine();
            if (header == null) {
                log.error("KB file empty");
                return;
            }

            String line;
            while ((line = br.readLine()) != null) {
                FieldInfo f = parseLine(line);
                if (f == null) continue;

                newKb.put(f.canonicalField, f);
                newCanonicalIndex.put(f.canonicalField, f);

                // alias 倒排索引
                if (f.aliases != null) {
                    for (String a : f.aliases.split("[,;|]")) {
                        String key = a.trim().toLowerCase();
                        if (key.length() == 0) continue;

                        newAliasIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error loading KB", e);
            return;
        }

        // 替换旧索引
        kb.clear();
        kb.putAll(newKb);

        canonicalIndex.clear();
        canonicalIndex.putAll(newCanonicalIndex);

        aliasIndex.clear();
        aliasIndex.putAll(newAliasIndex);

        lastLoadTime = System.currentTimeMillis();
        log.info("KB loaded: {} fields", kb.size());

        /** ---- BM25 重建索引 ---- **/
        bm25Service.buildIndex(kb.values());
        log.info("BM25 index rebuilt");

    }

    /** ----------- CSV 解析 ----------- **/
    private FieldInfo parseLine(String line) {
        try {
            List<String> parts = parseCsv(line);
            if (parts.size() < 7) return null;

            FieldInfo f = new FieldInfo();
//            f.canonicalField = parts.get(0);
//            f.columnName = parts.get(1);
//            f.aliases = parts.get(2);
//            f.description = parts.get(3);
//            f.remark = parts.get(4);
//            f.priorityLevel = Integer.parseInt(parts.get(5));

            String[] array = parts.stream().toArray(String[]::new);
            f.canonicalField = array[0];
            f.columnName = array[1];
            f.dataType = array[2];
            f.length = array[3];
            f.description = array[4];
            f.aliases = array[5];
            f.remark = array[6];
            f.priorityLevel = parseInt(array[7]);
            f.embedding = parseEmbedding(array[8]);

//            // embedding
//            String embStr = parts.get(6);
//            if (embStr != null && !embStr.isEmpty()) {
//                String[] arr = embStr.split(" ");
//                float[] vec = new float[arr.length];
//                for (int i = 0; i < arr.length; i++) {
//                    vec[i] = Float.parseFloat(arr[i]);
//                }
//                f.embedding = vec;
//            }

            return f;

        } catch (Exception e) {
            log.error("Error parsing KB line: {}", line);
            return null;
        }
    }

    /** 简单 CSV split 支持双引号 */
    private List<String> parseCsv(String line) {
        List<String> list = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }

            if (c == ',' && !inQuotes) {
                list.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        list.add(sb.toString());
        return list;
    }

    /** ----------- 监听 CSV 热更新 ----------- **/
    private void watchKbFile() {
        executor.submit(() -> {
            try {
                Path path = Paths.get("src/main/resources/data");

                WatchService ws = FileSystems.getDefault().newWatchService();
                path.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);

                while (true) {
                    WatchKey key = ws.take(); // block until modify

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        log.info("监听开始: Loading KB from {}", changed);
                        if (changed.toString().equals("kb_with_embedding.csv")) {
                            log.info("KB file changed, reloading...");
                            loadKbAsync();
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                log.error("watchKbFile error", e);
            }
        });
    }

    /** ----------- Public API ----------- **/

    /** 你缺的：通过 canonical 获取 FieldInfo */
    public FieldInfo getByCanonical(String canonical) {
        if (canonical == null) return null;
        return canonicalIndex.get(canonical);
    }

    /** 获取所有字段 */
    public Collection<FieldInfo> all() {
        return kb.values();
    }

    /** alias 倒排检索 */
    public List<FieldInfo> searchByAlias(String alias) {
        if (alias == null) return Collections.emptyList();
        return aliasIndex.getOrDefault(alias.trim().toLowerCase(), Collections.emptyList());
    }


    //------------------------------------------
    // CSV 工具方法
    //------------------------------------------

    private float[] parseEmbedding(String text) {
        try {
            String[] arr = text.split(",");
            float[] v = new float[arr.length];
            for (int i = 0; i < arr.length; i++) {
                v[i] = Float.parseFloat(arr[i]);
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); }
        catch (Exception e) { return 0; }
    }
}
