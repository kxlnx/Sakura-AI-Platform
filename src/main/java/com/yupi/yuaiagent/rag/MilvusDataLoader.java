package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Milvus 数据初始化流水线
 * 双通道入库：
 *   通道1 - 绘梨衣角色文档：语义分块 + LLM 关键词提取（慢，高质量）
 *   通道2 - Wiki 百科文档：标题切分，无关键词提取（快，大量数据）
 */
@Component
@Slf4j
public class MilvusDataLoader implements ApplicationRunner {

    @Resource
    private VectorStore vectorStore;

    @Resource
    private WikiDocumentLoader wikiDocumentLoader;

    @org.springframework.beans.factory.annotation.Value("${sakura.wiki.batch-size:30}")
    private int batchSize;

    @org.springframework.beans.factory.annotation.Value("${sakura.wiki.batch-delay-ms:200}")
    private int batchDelayMs;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("=========================================");
        log.info("[Milvus RAG] 开始执行 Wiki 入库流水线...");
        ingestWikiDocs();
        log.info("=========================================");
    }

    /** Wiki 百科文档（标题切分，无关键词，数千文件） */
    private void ingestWikiDocs() {
        // 已有数据则跳过，避免每次启动重复入库
        try {
            var exist = vectorStore.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder()
                    .query("check").topK(1)
                    .filterExpression(new FilterExpressionBuilder().eq("source", "wiki").build())
                    .build());
            if (!exist.isEmpty()) { log.info("[Wiki] 已有数据，跳过"); return; }
        } catch (Exception e) { log.info("[Wiki] 集合暂不可用，继续加载"); }

        log.info("[Wiki] ① 加载文档...");
        List<Document> docs = wikiDocumentLoader.loadWikiDocuments();
        if (docs.isEmpty()) {
            log.warn("[Wiki] 未找到任何 Wiki 文档");
            return;
        }

        log.info("[Wiki] ② 直接入库，共 {} 个切片", docs.size());
        batchWrite(docs, "Wiki");
    }

    /** 分批写入 Milvus */
    private void batchWrite(List<Document> docs, String label) {
        log.info("[{}] 准备入库，总切片数：{}，每批 {} 条", label, docs.size(), batchSize);

        for (int i = 0; i < docs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, docs.size());
            List<Document> batch = docs.subList(i, end);
            vectorStore.add(batch);

            if ((i / batchSize) % 10 == 0) {
                log.info("[{}] 已写入 {} / {} 条", label, end, docs.size());
            }

            try { Thread.sleep(batchDelayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        log.info("[{}] 入库完成！共 {} 条", label, docs.size());
    }
}
