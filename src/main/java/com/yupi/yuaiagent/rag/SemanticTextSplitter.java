package com.yupi.yuaiagent.rag;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语义分块器：基于 Embedding 相似度寻找语义断点
 */
@Component
public class SemanticTextSplitter {

    private final EmbeddingModel embeddingModel;

    // 相似度下降阈值，越小切分越细
    private static final double BREAKPOINT_THRESHOLD_PERCENTILE = 0.8;

    public SemanticTextSplitter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<Document> split(List<Document> documents) {
        List<Document> finalChunks = new ArrayList<>();
        for (Document doc : documents) {
            finalChunks.addAll(splitDocument(doc));
        }
        return finalChunks;
    }

    private List<Document> splitDocument(Document document) {
        String text = document.getText();
        // 1. 简单按句号、换行拆分句子（生产环境建议使用 NLP 工具如 HanLP）
        String[] sentences = text.split("(?<=[。！？\n])");
        if (sentences.length <= 1) return List.of(document);

        // 2. 获取所有句子的 Embedding
        List<Double[]> embeddings = embeddingModel.embed(List.of(sentences))
                .stream()
                .map(e -> box(e))
                .toList();

        // 3. 计算相邻句子的余弦相似度
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < embeddings.size() - 1; i++) {
            distances.add(cosineSimilarity(embeddings.get(i), embeddings.get(i + 1)));
        }

        // 4. 寻找语义断点并分块
        List<Document> chunks = new ArrayList<>();
        StringBuilder currentChunkText = new StringBuilder();
        currentChunkText.append(sentences[0]);

        for (int i = 0; i < distances.size(); i++) {
            // 如果相似度低于阈值，则认为语义发生漂移，执行切分
            if (distances.get(i) < BREAKPOINT_THRESHOLD_PERCENTILE) {
                chunks.add(new Document(currentChunkText.toString(), document.getMetadata()));
                currentChunkText = new StringBuilder();
            }
            currentChunkText.append(sentences[i + 1]);
        }

        if (!currentChunkText.isEmpty()) {
            chunks.add(new Document(currentChunkText.toString(), document.getMetadata()));
        }

        return chunks;
    }

    // 余弦相似度计算逻辑
    private double cosineSimilarity(Double[] vectorA, Double[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private Double[] box(float[] f) {
        Double[] d = new Double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = (double) f[i];
        return d;
    }
}