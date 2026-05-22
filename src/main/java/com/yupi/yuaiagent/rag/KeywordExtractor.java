package com.yupi.yuaiagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 中文关键词提取工具类（三合一：HybridSearchDocumentRetriever / LongTermMemoryReader / MilvusHybridSearchService）
 */
public final class KeywordExtractor {

    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_KEYWORDS = 10;

    private KeywordExtractor() {}

    public static List<String> extract(String query) {
        return extract(query, new String[0]);
    }

    public static List<String> extract(String query, String[] presets) {
        if (query == null || query.isBlank()) return List.of();

        List<String> keywords = new ArrayList<>();

        // 预设关键词匹配
        for (String preset : presets) {
            if (query.contains(preset)) keywords.add(preset);
        }

        // 中文：提取连续汉字 >= 2 个作为候选词
        StringBuilder sb = new StringBuilder();
        for (char c : query.toCharArray()) {
            if (Character.isIdeographic(c)) {
                sb.append(c);
            } else {
                if (sb.length() >= MIN_KEYWORD_LENGTH) keywords.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() >= MIN_KEYWORD_LENGTH) keywords.add(sb.toString());

        // 英文/数字：按空格和标点拆分
        for (String part : query.split("[\\s,，。！？?、]+")) {
            part = part.trim().toLowerCase();
            if (part.length() >= MIN_KEYWORD_LENGTH
                    && !part.matches("^[哪谁什么怎为啥的了吗呢啊吧嘛]+$")) {
                keywords.add(part);
            }
        }

        return keywords.stream().distinct().limit(MAX_KEYWORDS).collect(Collectors.toList());
    }
}
