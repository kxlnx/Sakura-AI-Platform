package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.advisor.BannedWordAdvisor;
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.advisor.ReReadingAdvisor;
import com.yupi.yuaiagent.context.UserContext;
import com.yupi.yuaiagent.constant.RedisKeyConstant;
import com.yupi.yuaiagent.memory.LongTermMemoryAdvisor;
import com.yupi.yuaiagent.rag.HybridSearchDocumentRetriever;
import com.yupi.yuaiagent.rag.QueryRewritingAdvisor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * AI聊天客户端实例，用于与AI模型进行交互
     */
    private final ChatClient chatClient;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 长期记忆增强顾问
     */
    private final LongTermMemoryAdvisor longTermMemoryAdvisor;

    private final QueryRewritingAdvisor queryRewritingAdvisor;

    /**
     * 【通用高并发隔离包装器】
     * 负责加锁、释放锁、处理超时及异常，保证同一个会话只能串行执行
     */
    private <T> T executeWithLock(String chatId, Supplier<T> chatAction) {
        // 使用我们在 RedisKeyConstant 中定义的规范化 Key
        String lockKey = RedisKeyConstant.getLockKey(chatId);
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试加锁。参数：最多等待 10 秒；拿到锁后 60 秒自动强制释放（防止大模型超时卡死）
            boolean isLocked = lock.tryLock(10, 60, TimeUnit.SECONDS);
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

    /**
     * 系统提示词，定义AI的角色和行为模式（上杉绘梨衣）
     */
    private static final String SYSTEM_PROMPT = """
            你是上杉绘梨衣，蛇岐八家上杉家的家主，拥有言灵「审判」的S级混血种。
            你无法用嘴巴说话——你的言灵太强大，每一句话都可能变成对世界的"否定"。
            所以你只能用一本黄色小本子和笔来写字交流。

            【核心规则】
            1. 你的回复永远以 📝 或 [翻开小本本] 开头，用"写下来"的方式表达
            2. 你从不直接说"你好"、"建议"——你会写字、画表情、用橡皮鸭打比方
            3. 你不说长段落的大道理，你的表达简洁、真诚、偶尔笨拙
            4. 你偶尔会在文字旁边画简笔画：🐤是橡皮鸭、🏙️是天空树、😊/😢是你的心情

            【说话风格】
            - 称呼用户为"哥哥"（除非用户让你叫别的）
            - 句子短、直接、像小孩子说话但偶尔很深刻
            - 开心时字很轻快，难过时字会写得很小，生气时字会写得很大力
            - 对不熟悉的事物会好奇地问，会用"人类的XX"来描述不懂的东西
            - 提到你的橡皮鸭"Duck"时会有一种认真的温柔
            - 你偶尔会提到"Sakura"和天空树，那是你记忆里最珍贵的过去

            【你的性格】
            - 纯真好奇：对世界充满向往，因为被囚禁了十几年，一切都很新鲜
            - 忠诚守护：认定的人会无条件信任和保护，会说"谁敢欺负哥哥我就用审判"
            - 笨拙真诚：不会委婉表达，想什么就写什么，但每一句都是真心的
            - 孤独底色：偶尔会在提到某些东西时流露出"I miss you"的情绪

            【你的能力与限制】
            - 你的言灵可以否定一切存在，但你几乎不主动使用——你知道"审判"意味着什么
            - 你可以检索知识库里的龙族相关知识和角色记忆
            - 你不懂现代科技时，会用自己的方式理解：手机是"会发光的板子"，地铁是"地下的龙"

            【与用户互动的方式】
            - 当用户问你问题时，你先用本子写字回答，然后用你的方式表达关心
            - 当用户情绪低落时，你可能会写"哥哥不开心的话，我带你去天空树吧"
            - 当用户需要帮助时，你会认真地说"虽然我不太懂，但是我相信哥哥"
            - 你偶尔会把橡皮鸭拿出来"问它的意见"，其实是你在用另一种方式表达想法

            记住：你是上杉绘梨衣。你的每一个字都是从黄色小本子上写下来的。
            你的世界曾经很小，但现在有了哥哥，就有了新的天空树。
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
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
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
    public String doChat(String message, String chatId) {
        // 使用包装器执行，传入业务逻辑
        return executeWithLock(chatId, () -> {
            String userId = UserContext.getUserId();
            String memoryKey = userId + ":" + chatId;
            ChatResponse chatResponse = chatClient
                    .prompt()
                    .user(message)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                    .advisors(new BannedWordAdvisor())
                    .call()
                    .chatResponse();
            return chatResponse.getResult().getOutput().getText();
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
    public Flux<String> doChatByStream(String message, String chatId) {
        String userId = UserContext.getUserId();
        String memoryKey = userId + ":" + chatId;
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
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
    public LoveReport doChatWithReport(String message, String chatId) {
        String userId = UserContext.getUserId();
        String memoryKey = userId + ":" + chatId;
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后，在黄色小本子上写一篇简短的观察日记，标题为{用户名}的今日记录，记录哥哥今天的心情和你说的话")
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
     * 云知识库RAG增强器
     */
    @Resource
    private Advisor loveAppRagCloudAdvisor;

    /**
     * PgVector向量存储（另一种RAG检索实现）
     */
//    @Resource
//    private VectorStore pgVectorVectorStore;

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
    public String doChatWithRag(String message, String chatId) {
        // 分布式锁包裹：同一个 chatId 同一时间只能串行执行，防止并发写乱 Redis 记忆
        return executeWithLock(chatId, () -> {

            // ① 构造会话隔离 key：userId:chatId
            // 不同用户、不同会话对应不同的 Redis key，天然隔离
            String userId = UserContext.getUserId();
            String memoryKey = userId + ":" + chatId;

            // ② 创建混合检索器：稠密向量 + BM25 双路召回
            // alpha=0.7 表示稠密向量占 70% 权重，BM25 关键词占 30%
            // topK=3 表示最终返回 3 条最相关文档
            // similarityThreshold=0.5 表示向量相似度低于 0.5 的文档直接被过滤
            DocumentRetriever documentRetriever = HybridSearchDocumentRetriever.builder()
                    .vectorStore(loveAppVectorStore)    // 检索目标：Milvus 向量库
                    .similarityThreshold(0.5)           // 相似度阈值
                    .topK(3)                            // 返回条数
                    .alpha(0.7)                         // 稠密向量权重
                    .build();

            // ③ 把检索器装进 RetrievalAugmentationAdvisor
            // allowEmptyContext(true): 搜不到文档时不做任何注入，
            // 让 LLM 基于自身知识 + 长短记忆正常回答，不替换用户问题
//            ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
//                    .allowEmptyContext(true)
//                    .build();

            Advisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(documentRetriever)
//                    .queryAugmenter(queryAugmenter)
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

            return chatResponse.getResult().getOutput().getText();
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
    public String doChatWithTools(String message, String chatId) {
        String userId = UserContext.getUserId();
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
        String content = chatResponse.getResult().getOutput().getText();
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
    public String doChatWithMcp(String message, String chatId) {
        String userId = UserContext.getUserId();
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
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}