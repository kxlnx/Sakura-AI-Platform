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

        try {
            List<Document> existingDocs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("check_existence")
                    .topK(1)
                    .build());
            if (!existingDocs.isEmpty()) {
                log.info("[Milvus RAG] 检测到向量数据库中已有数据，跳过初始化...");
                log.info("=========================================");
                return;
            }
        } catch (Exception e) {
            log.info("[Milvus RAG] 集合尚未创建或无法访问，继续初始化流程...");
        }

        // 1. 加载本地 Markdown 原始文档
        log.info("[Milvus RAG] ① 加载文档...");
        List<Document> documentList = loveAppDocumentLoader.loadMarkdowns();
        log.info("[Milvus RAG] ① 加载完成，共 {} 篇文档", documentList.size());

        // 2. 语义分块（调 Embedding API，可能需要 1-2 分钟）
        log.info("[Milvus RAG] ② 语义分块中...");
        List<Document> splitDocuments = semanticTextSplitter.split(documentList);
        log.info("[Milvus RAG] ② 分块完成，共 {} 个切片", splitDocuments.size());

        // 3. LLM 关键词提取（每个切片调一次 LLM，196 片约需 3-5 分钟）
        log.info("[Milvus RAG] ③ 关键词提取中（较慢，请等待）...");
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(splitDocuments);
        log.info("[Milvus RAG] ③ 关键词提取完成");

        // 4. 分批批量写入 Milvus
        int batchSize = 20;
        log.info("[Milvus RAG] ④ 开始入库，总切片数：{}，每批次 {} 条", enrichedDocuments.size(), batchSize);

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