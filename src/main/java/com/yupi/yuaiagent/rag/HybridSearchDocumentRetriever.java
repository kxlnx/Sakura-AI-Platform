package com.yupi.yuaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器：稠密向量搜索 + BM25 关键词搜索 → 加权融合
 * <p>
 * 实现 "双路召回 → 分数融合 → TopK" 的完整 RAG 检索链路。
 * 融合公式：最终得分 = α × 归一化稠密分数 + (1-α) × 归一化 BM25 分数
 */
@Slf4j
public class HybridSearchDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final double similarityThreshold;
    private final int topK;
    private final double alpha;

    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;
    private static final int AVG_DOC_LENGTH = 100;

    public HybridSearchDocumentRetriever(VectorStore vectorStore,
                                         double similarityThreshold,
                                         int topK,
                                         double alpha) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
        this.alpha = alpha;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<Document> retrieve(Query query) {
        String queryText = query.text();
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        // ① 稠密向量搜索（宽召回，topK * 3）
        List<Document> denseResults = denseSearch(queryText, topK * 3);

        // ② 提取关键词
        List<String> keywords = extractKeywords(queryText);

        // ③ 对稠密搜索结果计算 BM25 分数
        List<Bm25Item> bm25Items = computeBm25Scores(denseResults, keywords);

        // ④ 加权融合
        return fuseResults(denseResults, bm25Items, topK, alpha);
    }

    /**
     * 稠密向量语义搜索
     */
    private List<Document> denseSearch(String query, int limit) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .similarityThreshold(similarityThreshold)
                    .build();
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("[HybridRetriever] 稠密搜索失败", e);
            return List.of();
        }
    }

    /**
     * 提取查询中的关键词
     */
    private List<String> extractKeywords(String query) {
        List<String> keywords = new ArrayList<>();

        // 中文分词：提取连续汉字作为候选词
        StringBuilder sb = new StringBuilder();
        for (char c : query.toCharArray()) {
            if (Character.isIdeographic(c)) {
                sb.append(c);
            } else {
                if (sb.length() >= 2) {
                    keywords.add(sb.toString());
                }
                sb.setLength(0);
            }
        }
        if (sb.length() >= 2) {
            keywords.add(sb.toString());
        }

        // 英文/数字词
        for (String part : query.split("[\\s,，。！？?、]+")) {
            part = part.trim().toLowerCase();
            if (part.length() >= 2 && !part.matches("^[哪谁什么怎为啥的了吗呢啊吧嘛]+$")) {
                keywords.add(part);
            }
        }

        return keywords.stream().distinct().limit(10).collect(Collectors.toList());
    }

    /**
     * 对文档列表计算 BM25 分数
     */
    private List<Bm25Item> computeBm25Scores(List<Document> docs, List<String> keywords) {
        List<Bm25Item> items = new ArrayList<>();
        for (Document doc : docs) {
            double score = bm25Score(doc.getText(), keywords);
            if (score > 0) {
                items.add(new Bm25Item(doc, score));
            }
        }
        return items;
    }

    /**
     * 简化 BM25 计算
     */
    private double bm25Score(String text, List<String> keywords) {
        if (text == null || keywords.isEmpty()) {
            return 0;
        }
        double score = 0;
        int dl = text.length();
        for (String keyword : keywords) {
            int tf = countOccurrences(text, keyword);
            if (tf > 0) {
                double idf = 1.0; // 简化：假设所有词中等常见
                score += idf * (tf * (BM25_K1 + 1)) / (tf + BM25_K1 * (1 - BM25_B + BM25_B * dl / AVG_DOC_LENGTH));
            }
        }
        return score;
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    /**
     * 加权融合：α × dense_rank + (1-α) × bm25_norm
     */
    private List<Document> fuseResults(List<Document> denseResults,
                                       List<Bm25Item> bm25Items,
                                       int k,
                                       double alpha) {
        if (denseResults.isEmpty()) {
            return bm25Items.stream()
                    .sorted((a, b) -> Double.compare(b.score, a.score))
                    .limit(k)
                    .map(i -> i.doc)
                    .collect(Collectors.toList());
        }

        // 用 LinkedHashMap 保持插入顺序，合并去重
        Map<String, FusionItem> map = new LinkedHashMap<>();

        // 稠密向量：按排名归一化
        for (int i = 0; i < denseResults.size(); i++) {
            Document doc = denseResults.get(i);
            double normScore = 1.0 - (double) i / denseResults.size();
            map.put(doc.getText(), new FusionItem(doc, normScore * alpha));
        }

        // BM25：按分数归一化
        double maxBm25 = bm25Items.stream().mapToDouble(i -> i.score).max().orElse(1);
        for (Bm25Item item : bm25Items) {
            double normScore = maxBm25 > 0 ? item.score / maxBm25 : 0;
            String key = item.doc.getText();
            if (map.containsKey(key)) {
                map.get(key).score += normScore * (1 - alpha);
            } else {
                map.put(key, new FusionItem(item.doc, normScore * (1 - alpha)));
            }
        }

        List<Document> fused = map.values().stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(k)
                .map(i -> i.doc)
                .collect(Collectors.toList());

        log.info("[HybridRetriever] 稠密召回={}, BM25命中={}, 融合后={}",
                denseResults.size(), bm25Items.size(), fused.size());
        return fused;
    }

    private static class Bm25Item {
        final Document doc;
        final double score;
        Bm25Item(Document doc, double score) { this.doc = doc; this.score = score; }
    }

    private static class FusionItem {
        final Document doc;
        double score;
        FusionItem(Document doc, double score) { this.doc = doc; this.score = score; }
    }

    public static class Builder {
        private VectorStore vectorStore;
        private double similarityThreshold = 0.5;
        private int topK = 3;
        private double alpha = 0.7;

        public Builder vectorStore(VectorStore vectorStore) { this.vectorStore = vectorStore; return this; }
        public Builder similarityThreshold(double t) { this.similarityThreshold = t; return this; }
        public Builder topK(int k) { this.topK = k; return this; }
        public Builder alpha(double a) { this.alpha = a; return this; }

        public HybridSearchDocumentRetriever build() {
            if (vectorStore == null) throw new IllegalStateException("vectorStore is required");
            return new HybridSearchDocumentRetriever(vectorStore, similarityThreshold, topK, alpha);
        }
    }
}
