//package com.example.rag.service;
//
//import com.example.rag.model.FieldInfo;
//import org.apache.commons.text.similarity.JaroWinklerSimilarity;
//import org.springframework.stereotype.Service;
//
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Service
//public class MatcherService {
//    private final KbService kbService;
//    private final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();
//    // optional embedding client (see below)
//    // private final EmbeddingClient embeddingClient;
//
//    public MatcherService(KbService kbService) {
//        this.kbService = kbService;
//    }
//
//    public MatchResult match(String query) {
//        String qnorm = normalize(query);
//
//        // 1) correction override
//        Optional<String> corr = kbService.lookupCorrection(query);
//        if (corr.isPresent()) {
//            String c = corr.get();
//            FieldInfo info = kbService.getInfo(c);
//            return MatchResult.found(c, 1.0, "correction", info);
//        }
//
//        // 2) exact alias/canonical/column match
//        Optional<String> aliasHit = kbService.lookupAlias(query);
//        if (aliasHit.isPresent()) {
//            String c = aliasHit.get();
//            FieldInfo info = kbService.getInfo(c);
//            return MatchResult.found(c, 0.99, "alias", info);
//        }
//
//        // 3) string similarity search across aliases & canonical names
//        List<Candidate> candidates = new ArrayList<>();
//        for (FieldInfo fi : kbService.allFields()) {
//            // compare against canonical, columnName, and each alias token
//            double simCanon = jw.apply(qnorm, normalize(fi.canonicalField));
//            double simCol = jw.apply(qnorm, normalize(fi.columnName));
//            double bestAlias = Arrays.stream(fi.aliases.split(","))
//                    .map(String::trim)
//                    .filter(s -> !s.isEmpty())
//                    .mapToDouble(a -> jw.apply(qnorm, normalize(a)))
//                    .max().orElse(0.0);
//            double score = Math.max(simCanon, Math.max(simCol, bestAlias));
//            if (score > 0.85) candidates.add(new Candidate(fi, score));
//        }
//        if (!candidates.isEmpty()) {
//            candidates.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
//            Candidate best = candidates.get(0);
//            return MatchResult.found(best.field.canonicalField, best.score, "fuzzy", best.field);
//        }
//
//        // 4) optional: semantic embedding match (if enabled)
//        // embedding flow: compute query vector -> compute cosine with precomputed vectors -> best > threshold -> return
//
//        // 5) not found
//        return MatchResult.notFound();
//    }
//
//    private String normalize(String s) { return s == null ? "" : s.trim().toLowerCase().replaceAll("[_\\s]", ""); }
//
//    // helper classes
//    public static class Candidate { FieldInfo field; double score; Candidate(FieldInfo f,double s){this.field=f;this.score=s;} }
//    public static class MatchResult {
//        public boolean found; public String canonical; public double confidence; public String source; public FieldInfo info;
//        private MatchResult(boolean f){this.found=f;}
//        static MatchResult found(String c,double conf,String src, FieldInfo info){
//            MatchResult r=new MatchResult(true); r.canonical=c; r.confidence=conf; r.source=src; r.info=info; return r;
//        }
//        static MatchResult notFound(){ return new MatchResult(false); }
//    }
//}
