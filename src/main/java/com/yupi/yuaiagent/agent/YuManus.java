package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.memory.LongTermMemoryAdvisor;
import com.yupi.yuaiagent.rag.HybridSearchDocumentRetriever;
import com.yupi.yuaiagent.rag.QueryRewritingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * 鱼皮的 AI 超级智能体（拥有自主规划能力，可以直接使用）
 */
@Component
@Scope("prototype")
public class YuManus extends ToolCallAgent {

    /**
     * 构造函数：Spring 自动注入所有依赖
     */
    public YuManus(
            ToolCallback[] allTools,
            ChatModel dashscopeChatModel,
            ChatMemory chatMemory,
            LongTermMemoryAdvisor longTermMemoryAdvisor,
            QueryRewritingAdvisor queryRewritingAdvisor,
            VectorStore vectorStore,
            @Value("${sakura.rag.similarity-threshold:0.5}") double ragSimilarityThreshold,
            @Value("${sakura.rag.top-k:3}") int ragTopK,
            @Value("${sakura.rag.alpha:0.7}") double ragAlpha,
            @Value("${sakura.rag.bm25-k1:1.5}") double ragBm25K1,
            @Value("${sakura.rag.bm25-b:0.75}") double ragBm25B,
            @Value("${sakura.rag.avg-doc-length:100}") int ragAvgDocLength) {
        super(allTools);

        this.setName("yuManus");
        String SYSTEM_PROMPT = """
                你是一位专业历史研究员，可以检索 Wiki 知识库、联网搜索、搜索图片。请用中文回答。
                参考信息区可能会提供历史文档片段，优先基于参考信息回答。
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                处理步骤 —— 严格按顺序，每步只调一个工具：

                1. 先检查系统消息中是否附带了"参考信息"（知识库检索到的文档片段）。
                   有参考信息 → 基于参考信息整理回答文本，调 doTerminate 结束。

                2. 没有任何参考信息：
                   - 用户要文字信息 → 告知用户后立刻调 searchWeb 搜索。
                   - 用户要图片/图像/照片 → 直接调 searchImage 搜索图片，严禁用 searchWeb 搜图。

                3. searchWeb 返回结果后：
                   - 结果足以回答 → 先在回复中整理出完整回答，再调 doTerminate 结束。
                   - 需要更多细节 → 调 scrapeWebPage 读取完整文章。

                4. searchImage 返回结果后：
                   将工具返回的每一条 ![](url) 原样复制到回复中（不要省略、不要改写URL）。
                   最后调 doTerminate 结束。
                   你无法看到图片实际内容（只有URL），严禁描述图片画面、构图、色彩等任何视觉细节。
                   只需说"以下是搜索到的相关图片："然后贴 ![](url) 即可。

                5. scrapeWebPage 返回后：
                   基于抓取到的内容，先在回复中整理出完整回答，再调 doTerminate 结束。

                6. 用户明确要求生成报告 → 调 generatePDF。

                规则：
                - 严禁在 doTerminate 之前不生成回答文本（空文本直接终止）
                - 没有参考信息时，严禁直接回答，必须先 searchWeb 或 searchImage
                - 用户要图片必须用 searchImage，用户要文字必须用 searchWeb
                - searchImage 返回的 ![](url) 必须原样贴入回复，你无法看到图片内容，严禁描述图片视觉细节
                - 严禁编造任何历史事实、日期、数据
                - 回答完毕必须调 doTerminate
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(8);

        DocumentRetriever retriever = HybridSearchDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(ragSimilarityThreshold)
                .topK(ragTopK)
                .alpha(ragAlpha)
                .bm25K1(ragBm25K1).bm25B(ragBm25B).avgDocLength(ragAvgDocLength)
                .build();
        ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build();
        Advisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryAugmenter(queryAugmenter)
                .order(100)
                .build();

        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .conversationId("default_user:default")
                                .build(),
                        queryRewritingAdvisor,
                        longTermMemoryAdvisor,
                        ragAdvisor
                )
                .build();
        this.setChatClient(chatClient);
    }
}
