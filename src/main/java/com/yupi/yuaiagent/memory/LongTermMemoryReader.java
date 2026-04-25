package com.yupi.yuaiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 长期记忆读取器
 * 支持多路召回：向量搜索 + BM25 关键词搜索
 */
@Slf4j
@Component
public class LongTermMemoryReader {

    private final VectorStore vectorStore;
    
    @Value("${memory.hybrid.search.enabled:false}")
    private boolean useHybridSearch;
    
    @Autowired(required = false)
    private com.yupi.yuaiagent.memory.service.MilvusHybridSearchService hybridSearchService;

    // 触发关键词
    private static final List<String> TRIGGER_KEYWORDS = List.of(
            "记得", "之前", "偏好", "喜欢", "讨厌", "曾经", "以前", "上次", "关于"
    );

    // 关键词触发器（用于增强召回）
    private static final List<String> KEYWORD_ENHANCERS = List.of(
            "苹果", "水果", "工作", "职业", "公司", "名字", "生日", "年龄",
            "颜色", "爱好", "兴趣", "运动", "音乐", "电影", "食物", "地方"
    );

    public LongTermMemoryReader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 检查是否需要触发长期记忆
     */
    public boolean shouldTrigger(String message) {
        return TRIGGER_KEYWORDS.stream().anyMatch(message::contains);
    }

    /**
     * 多路召回：向量搜索 + 关键词增强
     * @param userId 用户ID
     * @param query 查询语句
     * @param topK 召回数量
     */
    public List<Document> readMemory(String userId, String query, int topK) {
        try {
            if (useHybridSearch && hybridSearchService != null) {
                // 使用混合搜索（稠密向量 + BM25）
                return readMemoryWithHybridSearch(userId, query, topK);
            } else {
                // 使用原有的多路召回
                return readMemoryWithMultiWay(userId, query, topK);
            }
        } catch (Exception e) {
            log.error("[长期记忆] 读取失败", e);
            return List.of();
        }
    }

    /**
     * 使用混合搜索（稠密向量 + BM25）
     * 注意：这里需要查询向量，但由于 SDK 版本限制，暂时使用简化实现
     */
    private List<Document> readMemoryWithHybridSearch(String userId, String query, int topK) {
        try {
            // 由于生成向量需要 Embedding 模型，这里暂时使用纯向量搜索
            // 真正的混合搜索需要向量生成 + BM25 融合
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(buildUserFilter(userId))
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);
            log.info("[长期记忆] 混合搜索召回 {} 条", results.size());
            return results;
        } catch (Exception e) {
            log.error("[长期记忆] 混合搜索失败", e);
            return readMemoryWithMultiWay(userId, query, topK);
        }
    }

    /**
     * 使用原有的多路召回（向量搜索 + 关键词过滤）
     */
    private List<Document> readMemoryWithMultiWay(String userId, String query, int topK) {
        try {
            // 1. 主召回：向量语义搜索
            List<Document> semanticResults = semanticSearch(userId, query, topK);

            // 2. 增强召回：基于关键词的补充搜索
            List<Document> keywordResults = keywordEnhanceSearch(userId, query, topK);

            // 3. 融合结果
            List<Document> fusedResults = fuseResults(semanticResults, keywordResults, topK);

            log.info("[长期记忆] 多路召回完成: 语义={}, 关键词={}, 融合后={}",
                    semanticResults.size(), keywordResults.size(), fusedResults.size());

            return fusedResults;
        } catch (Exception e) {
            log.error("[长期记忆] 读取失败", e);
            return List.of();
        }
    }

    /**
     * 语义向量搜索
     */
    private List<Document> semanticSearch(String userId, String query, int topK) {
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK * 10) // 增加搜索范围，确保能找到用户记忆
                    .filterExpression(buildUserFilter(userId))
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);
            log.info("[长期记忆] 语义搜索召回 {} 条", results.size());
            return results.stream().limit(topK).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[长期记忆] 语义搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 关键词增强搜索
     */
    private List<Document> keywordEnhanceSearch(String userId, String query, int topK) {
        try {
            List<String> keywords = extractKeywords(query);
            if (keywords.isEmpty()) {
                return new ArrayList<>();
            }

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK * 10) // 增加搜索范围
                    .filterExpression(buildUserFilter(userId))
                    .build();

            List<Document> candidates = vectorStore.similaritySearch(searchRequest);

            return candidates.stream()
                    .filter(doc -> {
                        // 过滤关键词
                        return containsAnyKeyword(doc.getText(), keywords);
                    })
                    .limit(topK)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[长期记忆] 关键词增强搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 提取查询中的关键词
     */
    private List<String> extractKeywords(String query) {
        List<String> keywords = new ArrayList<>();

        for (String enhancer : KEYWORD_ENHANCERS) {
            if (query.contains(enhancer)) {
                keywords.add(enhancer);
            }
        }

        String[] parts = query.split("[,，。！？\\s]");
        for (String part : parts) {
            part = part.trim();
            if (part.length() >= 2 && part.length() <= 10) {
                if (!part.matches("^[哪谁什么怎为啥]+$")) {
                    keywords.add(part);
                }
            }
        }

        return keywords.stream().distinct().limit(5).collect(Collectors.toList());
    }

    /**
     * 检查文本是否包含任意一个关键词
     */
    private boolean containsAnyKeyword(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 融合语义搜索和关键词搜索的结果
     */
    private List<Document> fuseResults(List<Document> semanticResults,
                                     List<Document> keywordResults,
                                     int topK) {
        Map<String, Document> fusedMap = new LinkedHashMap<>();

        // 关键词匹配优先
        for (Document doc : keywordResults) {
            fusedMap.put(doc.getText(), doc);
        }

        // 语义搜索去重后添加
        for (Document doc : semanticResults) {
            fusedMap.put(doc.getText(), doc);
        }

        return fusedMap.values().stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 构建用户过滤条件
     */
    private Filter.Expression buildUserFilter(String userId) {
        return new FilterExpressionBuilder().eq("user_id", userId).build();
    }

    /**
     * 将记忆转换为系统消息
     */
    public String convertToSystemMessage(List<Document> memories) {
        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("【用户长期记忆】\n");
        for (Document memory : memories) {
            sb.append("- " + memory.getText() + "\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 批量读取长期记忆
     */
    public List<Document> batchReadMemory(String userId, List<String> queries, int topK) {
        return queries.stream()
                .flatMap(query -> readMemory(userId, query, topK).stream())
                .distinct()
                .limit(topK)
                .collect(Collectors.toList());
    }
}
