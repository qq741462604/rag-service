package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.opencsv.CSVReader;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Service
public class KbService {

    // 存放 canonicalField -> FieldInfo
    private final Map<String, FieldInfo> kb = new HashMap<>();

    // alias 反查表 （alias -> canonicalField）
    private final Map<String, String> aliasIndex = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    // token -> list of canonicalFields
    private final Map<String, Set<String>> invertedIndex = new HashMap<>();
    // CSV 文件路径 —— 根据你自己的资源位置修改
    private static final String KB_FILE = "src/main/resources/data/kb_with_embedding.csv";

    //------------------------------------------
    // 初始化时异步加载 KB
    //------------------------------------------
    @PostConstruct
    @Async
    public void initAsync() {
        System.out.println("KB loading async…");
        loadKb();
        System.out.println("KB initial load done");
    }

    //------------------------------------------
    // 手动触发刷新
    //------------------------------------------
    public void reload() {
        loadKb();
    }

    //------------------------------------------
    // 每 10 分钟自动刷新（可调）
    //------------------------------------------
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void autoReload() {
        System.out.println("Auto reload KB…");
        loadKb();
    }

    //------------------------------------------
    // 核心加载逻辑（带写锁）
    //------------------------------------------
    public void loadKb() {
        System.out.println("Loading KB from " + KB_FILE);
        lock.writeLock().lock();
        try {
            CSVReader reader = new CSVReader(new FileReader(KB_FILE));
            Map<String, FieldInfo> newKb = new HashMap<>();
            Map<String, String> newAlias = new HashMap<>();

            String[] arr;
            boolean header = true;

            while ((arr = reader.readNext()) != null) {

                // 跳过 header
                if (header) {
                    header = false;
                    continue;
                }

                if (arr.length < 9) continue;

                FieldInfo f = new FieldInfo();
                f.canonicalField = arr[0];
                f.columnName = arr[1];
                f.dataType = arr[2];
                f.length = arr[3];
                f.description = arr[4];
                f.aliases = arr[5];
                f.remark = arr[6];
                f.priorityLevel = parseInt(arr[7]);
                f.embedding = parseEmbedding(arr[8]);

                newKb.put(f.canonicalField, f);

                for (String alias : f.aliases.split("\\|")) {
                    newAlias.put(alias.trim(), f.canonicalField);
                }
                String text = f.canonicalField + " " + f.columnName + " " + f.description + " " + f.aliases;
                for (String token : tokenize(text)) {
                    invertedIndex.computeIfAbsent(token, k -> new HashSet<>()).add(f.canonicalField);
                }


            }
            kb.clear();
            kb.putAll(newKb);

            aliasIndex.clear();
            aliasIndex.putAll(newAlias);

            System.out.println("KB reloaded: " + kb.size() + " rows");

        } catch (Exception e) {
            throw new RuntimeException("Failed to load KB", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    private List<String> tokenize(String text) {
        if (text == null) return new ArrayList<>();
        return Arrays.stream(text.toLowerCase().split("[^a-zA-Z0-9\u4e00-\u9fa5]+"))
                .filter(s -> s.length() > 1)
                .collect(Collectors.toList());
    }

    public Set<FieldInfo> fuzzyCandidates(String query) {
        Set<FieldInfo> result = new HashSet<>();
        for (String t : tokenize(query)) {
            Set<String> keys = invertedIndex.get(t);
            if (keys != null) {
                for (String k : keys) {
                    result.add(kb.get(k));
                }
            }
        }
        return result;
    }


    //------------------------------------------
    // 提供安全读取接口（读锁）
    //------------------------------------------
    public Optional<String> lookup(String alias) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(aliasIndex.get(alias));
        } finally {
            lock.readLock().unlock();
        }
    }

    public FieldInfo getInfo(String canonical) {
        lock.readLock().lock();
        try {
            return kb.get(canonical);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Collection<FieldInfo> all() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(kb.values());
        } finally {
            lock.readLock().unlock();
        }
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

    private String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') { inQuotes = !inQuotes; continue; }
            if (c == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }
}
