package com.example.rag.service;

import com.example.rag.model.FieldInfo;
import com.example.rag.util.MathUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class VectorSearchService {

    @Value("${vector.topk:5}")
    private int topK;

    @Value("${vector.threshold:0.60}")
    private double threshold;

    // 返回 topK 的 FieldInfo + score（用简单 Pair 结构）
    public List<Scored<FieldInfo>> search(float[] qv, Collection<FieldInfo> kb) {
        List<Scored<FieldInfo>> list = new ArrayList<Scored<FieldInfo>>();
        if (qv == null) return list;

        for (FieldInfo f : kb) {
            if (f.embedding == null) continue;
            double cos = MathUtil.cosine(qv, f.embedding);
            double score = cos + (f.priorityLevel * 0.01);
            if (score >= threshold) {
                list.add(new Scored<FieldInfo>(f, score));
            }
        }
        // sort by score desc
        Collections.sort(list, new Comparator<Scored<FieldInfo>>() {
            public int compare(Scored<FieldInfo> a, Scored<FieldInfo> b) {
                return Double.compare(b.score, a.score);
            }
        });

        // return topK
        if (list.size() > topK) {
            return list.subList(0, topK);
        } else {
            return list;
        }
    }

    public static class Scored<T> {
        public T item;
        public double score;
        public Scored(T i, double s) { item = i; score = s; }
    }
}
