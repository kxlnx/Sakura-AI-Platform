package com.yupi.yuaiagent.archived.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档加载器：从 classpath:document/*.md 加载 Markdown 并注入元数据
 * <p>
 * Metadata 三层加工链路：
 * ① 本类：注入 filename + title（从 H1 提取）
 * ② SemanticTextSplitter：透传，每个 chunk 继承父文档 metadata
 * ③ MyKeywordEnricher：LLM 提取关键词注入 keywords 字段
 */
@Component
@Slf4j
public class LoveAppDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                String rawContent = resource.getContentAsString(StandardCharsets.UTF_8);
                String title = extractTitle(rawContent);

                log.info("[文档加载] 文件: {}, 标题: {}", filename, title);

                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", filename)
                        .withAdditionalMetadata("title", title)
                        .withAdditionalMetadata("source", "character")
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }

    /**
     * 从 Markdown 正文提取 H1 标题
     * "# 上杉绘梨衣 · 角色设定" → "上杉绘梨衣·角色设定"
     */
    private String extractTitle(String markdown) {
        return markdown.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("#") && !line.startsWith("##"))
                .findFirst()
                .map(line -> line.replaceAll("^#+\\s*", "").replaceAll("\\s+", ""))
                .orElse("未分类");
    }
}
