package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.advisor.BannedWordAdvisor;
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.advisor.ReReadingAdvisor;
import com.yupi.yuaiagent.chatmemory.FileBasedChatMemory;
import com.yupi.yuaiagent.context.UserContext;
import com.yupi.yuaiagent.constant.RedisKeyConstant;
import com.yupi.yuaiagent.memory.LongTermMemoryAdvisor;
import com.yupi.yuaiagent.rag.QueryRewritingAdvisor;
import com.yupi.yuaiagent.rag.QueryRewritingService;
import com.yupi.yuaiagent.rag.LoveAppRagCustomAdvisorFactory;
import com.yupi.yuaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 恋爱顾问AI应用主组件
 * 负责处理恋爱相关的AI对话、知识库查询、工具调用及结构化报告生成等核心功能
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
     * 系统提示词，定义AI的角色和行为模式（恋爱心理专家）
     */
    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    /**
     * 初始化 ChatClient
     * 配置AI聊天客户端，设置系统提示词、对话记忆（基于内存的消息窗口）和默认增强器
     *
     * @param dashscopeChatModel AI聊天模型实例（如DashScope模型）
     */
    public LoveApp(ChatModel dashscopeChatModel, ChatMemory chatMemory, LongTermMemoryAdvisor longTermMemoryAdvisor) {
        this.longTermMemoryAdvisor = longTermMemoryAdvisor;

        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        longTermMemoryAdvisor
                        // 自定义日志 Advisor，可按需开启
//                        new MyLoggerAdvisor()
//                        // 自定义推理增强 Advisor，可按需开启
//                       ,new ReReadingAdvisor()
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
     * 恋爱报告数据结构
     * 用于封装AI生成的恋爱建议报告，包含标题和建议列表
     * Java 编译器就会自动为你生成包含 title 和 suggestions 的只读属性、全参构造函数、Getter 方法、equals、hashCode 以及 toString() 方法。
     *
     * {
     *   "title": "鱼皮的专属恋爱体检报告",
     *   "suggestions": [
     *     "每天主动分享一件趣事",
     *     "了解对方近期的工作压力"
     *   ]
     * }
     * @param title       报告标题（格式：{用户名}的恋爱报告）
     * @param suggestions 具体建议列表
     */
    record LoveReport(String title, List<String> suggestions) {

    }

    /**
     * AI 恋爱报告功能（实战结构化输出）
     * 生成结构化的恋爱建议报告，使用LoveReport类型接收AI输出
     *
     * @param message 用户输入消息
     * @param chatId  对话ID，用于标识不同对话会话
     * @return 结构化的恋爱报告对象
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        String userId = UserContext.getUserId();
        String memoryKey = userId + ":" + chatId;
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                .call()
                // 解析AI输出为结构化对象
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    // AI 恋爱知识库问答功能

    /**
     * 恋爱知识库向量存储（用于RAG检索）
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
     * 查询重写器，用于优化用户查询以提高RAG检索准确性
     */
    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 和 RAG 知识库进行对话
     * 结合检索增强生成（RAG）技术，使用知识库内容增强AI回答的准确性和专业性
     * userId 从 UserContext 自动获取（通常由登录过滤器设置）
     * 集成 Query Rewriting 策略，解决多轮对话中的指代性、省略性问题
     *
     * @param message 用户输入消息
     * @param chatId  对话ID，用于标识不同对话会话
     * @return 结合知识库内容的AI响应
     */
    public String doChatWithRag(String message, String chatId) {
        return executeWithLock(chatId, () -> {
            String userId = UserContext.getUserId();
            String memoryKey = userId + ":" + chatId;
            log.info("[doChatWithRag] userId={}, chatId={}, memoryKey={}, message={}", userId, chatId, memoryKey, message);
            
            // 构建查询（集成 Query Rewriting）
            String query = message;
            if (queryRewritingService.needRewriting(message)) {
                // 使用历史对话进行重写
                query = queryRewritingService.rewriteQuery(memoryKey, message);
                log.info("[doChatWithRag] 重写后的查询: {}", query);
            }
            
            ChatResponse chatResponse = chatClient
                    .prompt()
                    .user(query) // 使用重写后的查询
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, memoryKey))
                    .advisors(spec -> spec.param("userId", userId))
                    .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))
                    .call()
                    .chatResponse();
            String content = chatResponse.getResult().getOutput().getText();
            log.info("content: {}", content);
            return content;
        });
    }

    // AI 调用工具能力

    /**
     * 所有可用工具的回调数组（用于AI工具调用功能）
     */
    @Resource
    private QueryRewritingService queryRewritingService;

    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 恋爱报告功能（支持调用工具）
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
     * AI 恋爱报告功能（调用 MCP 服务）
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