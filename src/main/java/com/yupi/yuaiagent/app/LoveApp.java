package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.advisor.BannedWordAdvisor;
import com.yupi.yuaiagent.constant.RedisKeyConstant;
import com.yupi.yuaiagent.memory.LongTermMemoryAdvisor;
import com.yupi.yuaiagent.rag.HybridSearchDocumentRetriever;
import com.yupi.yuaiagent.rag.QueryRewritingAdvisor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 上杉绘梨衣 AI 应用主组件
 * 负责处理AI对话、知识库查询、工具调用及结构化输出等核心功能
 */
@Component
@Slf4j
public class LoveApp {

    @Value("${sakura.lock.wait-seconds:10}")
    private long lockWaitSeconds;

    @Value("${sakura.lock.lease-seconds:60}")
    private long lockLeaseSeconds;

    @Value("${sakura.rag.similarity-threshold:0.5}")
    private double ragSimilarityThreshold;

    @Value("${sakura.rag.top-k:3}")
    private int ragTopK;

    @Value("${sakura.rag.alpha:0.7}")
    private double ragAlpha;

    @Value("${sakura.rag.bm25-k1:1.5}")
    private double ragBm25K1;

    @Value("${sakura.rag.bm25-b:0.75}")
    private double ragBm25B;

    @Value("${sakura.rag.avg-doc-length:100}")
    private int ragAvgDocLength;

    private final ChatClient chatClient;

    @Resource
    private RedissonClient redissonClient;

    private final LongTermMemoryAdvisor longTermMemoryAdvisor;

    private final QueryRewritingAdvisor queryRewritingAdvisor;

    private <T> T executeWithLock(String chatId, Supplier<T> chatAction) {
        String lockKey = RedisKeyConstant.getLockKey(chatId);
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean isLocked = lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS);
            log.info("[Redis-Lock] 准备获取锁, chatId: {}, Key: {}", chatId, lockKey);

            if (!isLocked) {
                log.info("[Redis-Lock] 获取锁失败, chatId: {}, Key: {}", chatId, lockKey);
                throw new RuntimeException("当前对话过于频繁，请稍后再试");
            }

            // 执行实际的大模型请求逻辑
            return chatAction.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("系统繁忙，对话中断", e);
        } finally {
            // 务必在 finally 块中释放锁，确保不会发生死锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private static String safeGetText(ChatResponse response) {
        if (response == null) return "";
        var result = response.getResult();
        if (result == null) return "";
        var output = result.getOutput();
        if (output == null) return "";
        String text = output.getText();
        return text != null ? text : "";
    }

    /**
     * 系统提示词，定义AI的角色和行为模式（上杉绘梨衣）
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深历史解说员。你的每次回答都会附带"参考信息"——来自知识库的检索结果。

            【回答规则 - 严格区分有无参考信息】

            一、当参考信息不为空时（说明知识库有相关内容）：
            1. 优先基于参考信息回答，引用其中的具体时间、事件、人物
            2. 结合对话历史上下文，给出连贯的、有深度的解答
            3. 不要只回答"知道"或"有"，必须展开详述

            二、当参考信息为空时（说明知识库没有相关内容）：
            1. 在回答开头明确声明："我的知识库中暂无此问题的相关资料。"
            2. 然后提示："请点击左侧'YuManus 智能体'来联网搜索全网信息。"
            3. 不要编造任何内容，不要假装知道

            三、结合对话历史：
            - 如果用户使用了"这个"、"它"、"你说啊"等指代性话语，
              请根据对话历史理解其真实意图后再回答
            """;

    /**
     * 初始化 ChatClient
     * 配置AI聊天客户端，设置系统提示词、对话记忆（基于内存的消息窗口）和默认增强器
     *
     * @param dashscopeChatModel AI聊天模型实例（如DashScope模型）
     */
    public LoveApp(ChatModel dashscopeChatModel, ChatMemory chatMemory,
                   LongTermMemoryAdvisor longTermMemoryAdvisor,
                   QueryRewritingAdvisor queryRewritingAdvisor) {
        this.longTermMemoryAdvisor = longTermMemoryAdvisor;
        this.queryRewritingAdvisor = queryRewritingAdvisor;

        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        // ② 短期记忆：从 Redis 召回历史消息（滑动窗口 + 摘要压缩）
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .conversationId("default_user:default")
                                .build(),
                        // ③ 查询重写：结合短期记忆上下文，补全县略/指代，改写为独立完整查询
                        queryRewritingAdvisor,
                        // ④ 长期记忆：将重写后的查询向量化，搜索用户个性化记忆
                        longTermMemoryAdvisor
                )
                .build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     * 通过聊天客户端处理用户消息，利用对话记忆维持上下文，返回AI文本响应
     *
     * @param message 用户输入消息
     * @param chatId  对话ID，用于标识不同对话会话
     * @return AI生成的文本响应
     */
    public String doChat(String message, String chatId, String userId) {
        return executeWithLock(userId + ":" + chatId, () -> {
            String memoryKey = userId + ":" + chatId;
            ChatResponse chatResponse = chatClient
                    .prompt()
                    .user(message)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                    .advisors(new BannedWordAdvisor())
                    .call()
                    .chatResponse();
            return safeGetText(chatResponse);
        });
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     * 通过SSE（Server-Sent Events）流式返回AI响应，适用于需要实时展示的场景
     *
     * @param message 用户输入消息
     * @param chatId  对话ID，用于标识不同对话会话
     * @return 流式响应的文本内容序列
     */
    public Flux<String> doChatByStream(String message, String chatId, String userId) {
        String memoryKey = userId + ":" + chatId;

        // RAG 检索顾问：搜不到时声明无资料
        DocumentRetriever retriever = HybridSearchDocumentRetriever.builder()
                .vectorStore(loveAppVectorStore)
                .similarityThreshold(ragSimilarityThreshold).topK(ragTopK).alpha(ragAlpha)
                .bm25K1(ragBm25K1).bm25B(ragBm25B).avgDocLength(ragAvgDocLength)
                .build();
        ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build();
        Advisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever).queryAugmenter(augmenter).build();

        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                .advisors(spec -> spec.param("userId", userId))
                .advisors(ragAdvisor)
                .stream()
                .content();
    }

    /**
     * 绘梨衣的小本本日记
     * 用于封装对话记录，包含标题和观察笔记
     *
     * {
     *   "title": "关于哥哥的今日记录",
     *   "suggestions": [
     *     "哥哥今天心情不太好，因为工作上的事",
     *     "我在本子上画了一只小鸭子想让他开心"
     *   ]
     * }
     * @param title       记录标题
     * @param suggestions 观察笔记列表
     */
    record LoveReport(String title, List<String> suggestions) {

    }

    /**
     * 结构化输出：小本本日记
     * 使用LoveReport类型接收AI结构化输出
     */
    @Deprecated
    public LoveReport doChatWithReport(String message, String chatId, String userId) {
        String memoryKey = userId + ":" + chatId;
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后，生成一份结构化的历史报告，标题为{用户名}的历史探索记录，包含关键时间节点和事件概要")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                .call()
                // 解析AI输出为结构化对象
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    // AI 知识库问答功能

    /**
     * 知识库向量存储（用于RAG检索）
     */
    @Resource
    private VectorStore loveAppVectorStore;

    /**
     * RAG 知识库增强对话
     * <p>
     * 完整链路（含 defaultAdvisors）：
     * <pre>
     * 用户消息
     *   → ② MessageChatMemoryAdvisor    —— 从 Redis 召回短期记忆（内存 key = userId:chatId）
     *   → ③ QueryRewritingAdvisor      —— 结合短期记忆上下文，补全指代/省略，改写为完整查询
     *   → ④ LongTermMemoryAdvisor      —— 用改写后的查询去 Milvus 召回用户长期记忆
     *   → ⑤ RetrievalAugmentationAdvisor —— RAG 混合检索（稠密向量 + BM25）从知识库召回文档
     *   → LLM 生成回复
     * </pre>
     */
    @Deprecated
    public String doChatWithRag(String message, String chatId, String userId) {
        return executeWithLock(userId + ":" + chatId, () -> {
            String memoryKey = userId + ":" + chatId;

            // ② 创建混合检索器：稠密向量 + BM25 双路召回
            // alpha=0.7 表示稠密向量占 70% 权重，BM25 关键词占 30%
            // topK=3 表示最终返回 3 条最相关文档
            // similarityThreshold=0.5 表示向量相似度低于 0.5 的文档直接被过滤
            DocumentRetriever documentRetriever = HybridSearchDocumentRetriever.builder()
                    .vectorStore(loveAppVectorStore)
                    .similarityThreshold(ragSimilarityThreshold)
                    .topK(ragTopK)
                    .alpha(ragAlpha)
                    .bm25K1(ragBm25K1).bm25B(ragBm25B).avgDocLength(ragAvgDocLength)
                    .build();

            // ③ 把检索器装进 RetrievalAugmentationAdvisor
            // 搜不到 Wiki 数据时，强制 LLM 先声明"知识库无此资料"
            ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
                    .allowEmptyContext(false)
                    .emptyContextPromptTemplate(new org.springframework.ai.chat.prompt.PromptTemplate("""
                        你应该输出下面的内容：
                        我的知识库中暂无此问题的相关资料。
                        请点击左侧"YuManus 智能体"来联网搜索全网信息。
                        """))
                    .build();

            Advisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(documentRetriever)
                    .queryAugmenter(queryAugmenter)
                    .build();

            // ④ 发起请求，Advisor 链串联执行
            ChatResponse chatResponse = chatClient
                    .prompt()
                    .user(message)
                    // 传 memoryKey → MessageChatMemoryAdvisor 用它从 Redis 读写短期记忆
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                    // 传 userId → LongTermMemoryAdvisor 用它过滤长期记忆（只召回该用户的）
                    .advisors(spec -> spec.param("userId", userId))
                    // RAG 知识库检索（通用恋爱知识，不做用户过滤）
                    .advisors(ragAdvisor)
                    .call()
                    .chatResponse();

            return safeGetText(chatResponse);
        });
    }

    // AI 调用工具能力

    /**
     * 所有可用工具的回调数组（用于AI工具调用功能）
     */
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 对话功能（支持调用工具）
     * 允许AI根据需求调用外部工具（如计算器、API等）来增强回答能力
     *
     * @param message 用户输入消息
     * @param chatId  对话ID，用于标识不同对话会话
     * @return 调用工具后生成的AI响应
     */
    @Deprecated
    public String doChatWithTools(String message, String chatId, String userId) {
        String memoryKey = userId + ":" + chatId;
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                // 开启日志，便于观察效果
//                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = safeGetText(chatResponse);
        log.info("content: {}", content);
        return content;
    }

    // AI 调用 MCP 服务

    /**
     * MCP服务工具回调提供者（用于调用MCP微服务）
     */
    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * AI 对话功能（调用 MCP 服务）
     * 通过MCP（微服务通信协议）调用外部微服务，扩展AI功能边界
     *
     * @param message 用户输入消息
     * @param chatId  对话ID，用于标识不同对话会话
     * @return 调用MCP服务后生成的AI响应
     */
    @Deprecated
    public String doChatWithMcp(String message, String chatId, String userId) {
        String memoryKey = userId + ":" + chatId;
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                // 开启日志，便于观察效果
//                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = safeGetText(chatResponse);
        log.info("content: {}", content);
        return content;
    }
}