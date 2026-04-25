package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Milvus 数据初始化流水线
 * 实现 ApplicationRunner，项目启动成功后会自动执行一次 run 方法
 */
@Component
@Slf4j
public class MilvusDataLoader implements ApplicationRunner {

    // Spring AI Starter 会自动把刚才 yaml 里配好的 Milvus 实例注入进来
    @Resource
    private VectorStore vectorStore;

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private SemanticTextSplitter semanticTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("=========================================");
        log.info("[Milvus RAG] 开始执行工业级数据入库流水线...");

        List<Document> existingDocs = vectorStore.similaritySearch(SearchRequest.builder()
                .query("check_existence")
                .topK(1)
                .build());
        if (!existingDocs.isEmpty()) {
            log.info("[Milvus RAG] 检测到向量数据库中已有数据，跳过初始化...");
            log.info("=========================================");
            return;
        }

        // 1. 加载本地 Markdown 原始文档
        List<Document> documentList = loveAppDocumentLoader.loadMarkdowns();

        // 2. 物理切分 (Chunking)
        List<Document> splitDocuments = semanticTextSplitter.split(documentList);

        // 3. AI 语义增强 (提取关键词注入 Metadata)
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(splitDocuments);

        // 4. 分批批量写入 Milvus（应对大模型 API 单次限制）
        int batchSize = 20; // 阿里云限制最大25，我们设为20更安全
        log.info("[Milvus RAG] 准备入库，总切片数：{}，每批次 {} 条", enrichedDocuments.size(), batchSize);

        for (int i = 0; i < enrichedDocuments.size(); i += batchSize) {
            // 计算当前批次的结束位置
            int end = Math.min(i + batchSize, enrichedDocuments.size());
            // 截取一小批数据
            List<Document> batch = enrichedDocuments.subList(i, end);

            // 写入向量库
            vectorStore.add(batch);
            log.info("[Milvus RAG] 成功写入批次: {} 到 {} 条", i, end);

            // [进阶细节] 如果你的阿里云免费额度有 QPS (每秒请求数) 限制，可以加上短暂休眠防止被封杀
            try {
                Thread.sleep(500); // 停顿 0.5 秒
            } catch (InterruptedException e) {
                log.error("入库休眠被中断", e);
            }
        }

        log.info("[Milvus RAG] 数据成功写入向量数据库！共入库 {} 个高维知识切片", enrichedDocuments.size());
        log.info("=========================================");
    }
}