package com.yupi.yuaiagent.memory.service;

import com.yupi.yuaiagent.rag.KeywordExtractor;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Milvus 混合搜索服务
 * 实现稠密向量搜索 + BM25 稀疏搜索的加权融合
 *
 * 融合公式：最终得分 = α × 稠密向量相似度 + (1-α) × BM25分数
 *
 * 注意：需要 Milvus 2.5+ 版本支持 BM25 稀疏向量
 */
@Slf4j
@Service
public class MilvusHybridSearchService {

    private final VectorStore vectorStore;
    private final String collectionName;
    private final double alpha; // 稠密向量权重，BM25权重 = 1 - alpha

    // 默认配置
    private static final String DEFAULT_COLLECTION = "love_master_knowledge";
    private static final double DEFAULT_ALPHA = 0.7;

    public MilvusHybridSearchService(
            VectorStore vectorStore,
            @Value("${spring.ai.vectorstore.milvus.collection-name:love_master_knowledge}") String collectionName,
            @Value("${memory.hybrid.search.alpha:0.7}") double alpha) {
        this.vectorStore = vectorStore;
        this.collectionName = collectionName;
        this.alpha = alpha;
    }

    /**
     * 执行混合搜索
     * @param userId 用户ID
     * @param query 查询文本
     * @param queryVector 查询向量（稠密向量）
     * @param topK 返回数量
     * @return 融合后的文档列表
     */
    public List<Document> hybridSearch(String userId, String query, float[] queryVector, int topK) {
        try {
            // 1. 稠密向量搜索
            List<Document> denseResults = denseVectorSearch(userId, query, queryVector, topK);

            // 2. BM25 稀疏搜索（如果支持）
            List<BM25Result> bm25Results = bm25SparseSearch(userId, query, topK);

            // 3. 分数融合
            return fuseResults(denseResults, bm25Results, topK);

        } catch (Exception e) {
            log.error("[混合搜索] 执行失败", e);
            // 降级：只返回向量搜索结果
            return fallbackToVectorSearch(userId, query, topK);
        }
    }

    /**
     * 稠密向量搜索
     */
    private List<Document> denseVectorSearch(String userId, String query, float[] queryVector, int topK) {
        try {
            Filter.Expression userFilter = new FilterExpressionBuilder().eq("user_id", userId).build();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK * 2)
                    .filterExpression(userFilter)
                    .build();

            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.error("[混合搜索] 稠密向量搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * BM25 稀疏搜索（基于关键词权重）
     * 由于 SDK 版本限制，这里使用基于关键词匹配的简化 BM25
     */
    private List<BM25Result> bm25SparseSearch(String userId, String query, int topK) {
        try {
            List<String> keywords = extractKeywords(query);
            if (keywords.isEmpty()) {
                return new ArrayList<>();
            }

            Filter.Expression userFilter = new FilterExpressionBuilder().eq("user_id", userId).build();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK * 2)
                    .filterExpression(userFilter)
                    .build();

            List<Document> candidates = vectorStore.similaritySearch(searchRequest);

            List<BM25Result> results = new ArrayList<>();
            for (Document doc : candidates) {
                double bm25Score = calculateBM25Score(doc.getText(), keywords);
                if (bm25Score > 0) {
                    results.add(new BM25Result(doc, bm25Score));
                }
            }

            results.sort((a, b) -> Double.compare(b.score, a.score));
            return results.subList(0, Math.min(results.size(), topK));

        } catch (Exception e) {
            log.error("[混合搜索] BM25稀疏搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 提取查询关键词
     */
    private static final String[] KEYWORD_PRESETS = {
        "苹果", "水果", "工作", "职业", "公司", "名字", "生日", "年龄",
        "颜色", "爱好", "兴趣", "运动", "音乐", "电影", "食物", "地方",
        "喜欢", "讨厌", "记得", "之前", "偏好"
    };

    private List<String> extractKeywords(String query) {
        return KeywordExtractor.extract(query, KEYWORD_PRESETS);
    }

    /**
     * 简化的 BM25 算法
     * BM25 公式：score = IDF × (tf × (k1 + 1)) / (tf + k1 × (1 - b + b × dl/avgdl))
     */
    private double calculateBM25Score(String document, List<String> keywords) {
        double score = 0;
        double k1 = 1.5;
        double b = 0.75;

        // 文档长度
        int dl = document.length();
        int avgdl = 100; // 假设平均文档长度

        for (String keyword : keywords) {
            int tf = countOccurrences(document, keyword);
            if (tf > 0) {
                // 简化 IDF（假设所有词都是中等常见的）
                double idf = 1.0;

                // BM25 公式
                double termScore = idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl));
                score += termScore;
            }
        }

        return score;
    }

    /**
     * 计算关键词在文档中的出现次数
     */
    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) != -1) {
            count++;
            index += keyword.length();
        }
        return count;
    }

    /**
     * 融合稠密向量和 BM25 结果
     * 融合公式：最终得分 = α × 归一化稠密分数 + (1-α) × 归一化BM25分数
     */
    private List<Document> fuseResults(List<Document> denseResults,
                                      List<BM25Result> bm25Results,
                                      int topK) {
        if (denseResults.isEmpty() && bm25Results.isEmpty()) {
            return new ArrayList<>();
        }

        if (denseResults.isEmpty()) {
            return bm25Results.stream().limit(topK).map(r -> r.document).toList();
        }

        if (bm25Results.isEmpty()) {
            return denseResults.stream().limit(topK).toList();
        }

        // 计算归一化分数并融合
        Map<String, FusionItem> fusionMap = new LinkedHashMap<>();

        // 处理稠密向量结果
        for (int i = 0; i < denseResults.size(); i++) {
            Document doc = denseResults.get(i);
            double normalizedScore = 1.0 - (double) i / denseResults.size(); // 排名归一化
            fusionMap.put(doc.getText(), new FusionItem(doc, normalizedScore * alpha, "dense"));
        }

        // 处理 BM25 结果
        double maxBm25 = bm25Results.stream().mapToDouble(r -> r.score).max().orElse(1);
        for (int i = 0; i < bm25Results.size(); i++) {
            BM25Result bm25Result = bm25Results.get(i);
            double normalizedScore = (maxBm25 > 0) ? bm25Result.score / maxBm25 : 0;
            String content = bm25Result.document.getText();

            if (fusionMap.containsKey(content)) {
                // 已存在，累加分数
                FusionItem existing = fusionMap.get(content);
                existing.score += normalizedScore * (1 - alpha);
                existing.sources.add("bm25");
            } else {
                fusionMap.put(content, new FusionItem(bm25Result.document, normalizedScore * (1 - alpha), "bm25"));
            }
        }

        // 按融合分数排序
        return fusionMap.values().stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .map(item -> item.document)
                .toList();
    }

    /**
     * 降级：只使用向量搜索
     */
    private List<Document> fallbackToVectorSearch(String userId, String query, int topK) {
        try {
            Filter.Expression userFilter = new FilterExpressionBuilder().eq("user_id", userId).build();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(userFilter)
                    .build();
            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.error("[混合搜索] 降级搜索也失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * BM25 结果
     */
    private static class BM25Result {
        Document document;
        double score;

        BM25Result(Document document, double score) {
            this.document = document;
            this.score = score;
        }
    }

    /**
     * 融合项
     */
    private static class FusionItem {
        Document document;
        double score;
        List<String> sources = new ArrayList<>();

        FusionItem(Document document, double score, String source) {
            this.document = document;
            this.score = score;
            this.sources.add(source);
        }
    }
}
