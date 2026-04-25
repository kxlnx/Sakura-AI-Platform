package com.yupi.yuaiagent.memory;

import com.yupi.yuaiagent.memory.service.MemoryExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆写入器
 * 使用 LLM 智能提取关键事实
 */
@Slf4j
@Component
public class LongTermMemoryWriter {

    private final VectorStore vectorStore;

    @Autowired(required = false)
    private MemoryExtractionService extractionService;

    // 相似度阈值，超过这个阈值认为记忆已存在
    private static final double SIMILARITY_THRESHOLD = 0.95;

    public LongTermMemoryWriter(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 写入长期记忆（带去重检查）
     * @param userId 用户ID
     * @param fact 事实内容
     * @param metadata 元数据
     */
    public void writeMemory(String userId, String fact, Map<String, Object> metadata) {
        try {
            // 1. 先检查是否已存在相似的记忆
            if (isSimilarMemoryExists(userId, fact)) {
                log.debug("[长期记忆] 检测到相似记忆已存在，跳过写入: userId={}", userId);
                return;
            }

            // 2. 构建文档元数据
            Map<String, Object> docMetadata = new HashMap<>();
            docMetadata.put("user_id", userId);
            docMetadata.put("timestamp", System.currentTimeMillis());
            docMetadata.put("memory_type", "long_term");
            docMetadata.put("content", fact); // 保存原始内容用于去重
            if (metadata != null) {
                docMetadata.putAll(metadata);
            }

            // 3. 创建文档
            Document document = new Document(fact, docMetadata);

            // 4. 写入向量库
            vectorStore.add(List.of(document));
            log.info("[长期记忆] 成功写入记忆: userId={}, fact={}", userId, fact);
        } catch (Exception e) {
            log.error("[长期记忆] 写入失败", e);
        }
    }

    /**
     * 检查是否已存在相似的记忆
     */
    private boolean isSimilarMemoryExists(String userId, String fact) {
        try {
            Filter.Expression userFilter = new FilterExpressionBuilder().eq("user_id", userId).build();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(fact)
                    .topK(5)
                    .filterExpression(userFilter)
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);

            for (Document doc : results) {
                double distance = getDistanceFromMetadata(doc);
                if (distance < (1 - SIMILARITY_THRESHOLD)) {
                    log.debug("[长期记忆] 发现相似记忆: distance={}, content={}", distance, doc.getText());
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("[长期记忆] 检查相似记忆时出错: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从元数据中获取距离
     */
    private double getDistanceFromMetadata(Document doc) {
        Object distance = doc.getMetadata().get("distance");
        if (distance != null) {
            return Double.parseDouble(distance.toString());
        }
        return 1.0; // 如果没有 distance，默认认为不相似
    }

    /**
     * 处理显式记忆指令
     * 用户说："请记住我喜欢吃苹果"
     */
    public void handleExplicitMemory(String userId, String message) {
        List<String> facts;

        // 优先使用 LLM 提取
        if (extractionService != null) {
            facts = extractionService.extractFromExplicitInstruction(message);
        } else {
            // 降级方案：简单规则提取
            facts = fallbackExtractExplicit(message);
        }

        // 写入每一条提取的事实
        for (String fact : facts) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("trigger_type", "explicit");
            metadata.put("original_message", message);
            writeMemory(userId, fact, metadata);
        }
    }

    /**
     * 处理隐式记忆提取
     * 用户说："我是一名程序员，在北京工作"
     */
    public void handleImplicitMemory(String userId, String message) {
        List<String> facts;

        // 优先使用 LLM 提取
        if (extractionService != null) {
            facts = extractionService.extractFromConversation(message);
        } else {
            // 降级方案：简单规则提取
            facts = fallbackExtractImplicit(message);
        }

        // 写入每一条提取的事实
        for (String fact : facts) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("trigger_type", "implicit");
            metadata.put("original_message", message);
            writeMemory(userId, fact, metadata);
        }
    }

    /**
     * 降级方案：简单的规则提取（显式）
     */
    private List<String> fallbackExtractExplicit(String message) {
        List<String> facts = new ArrayList<>();

        // 尝试提取"记住"后面的内容
        if (message.contains("记住")) {
            int index = message.indexOf("记住");
            String fact = message.substring(index + 2).trim();
            // 清理常见前缀
            fact = fact.replaceAll("^[：:，,]+\\s*", "");
            if (!fact.isEmpty()) {
                facts.add(fact);
            }
        }

        return facts;
    }

    /**
     * 降级方案：简单的规则提取（隐式）
     */
    private List<String> fallbackExtractImplicit(String message) {
        List<String> facts = new ArrayList<>();

        // 简单模式匹配
        String[] patterns = {
            "我叫([^，,。\\s]+)",
            "我的名字是([^，,。\\s]+)",
            "我是([^，,。]+)",
            "在([^，,。\\s]+)工作",
            "住在([^，,。\\s]+)",
            "喜欢([^，,。\\s]+)",
            "从事([^，,。\\s]+)工作"
        };

        for (String pattern : patterns) {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(message);
            if (m.find()) {
                facts.add(m.group(0));
            }
        }

        return facts;
    }
}
