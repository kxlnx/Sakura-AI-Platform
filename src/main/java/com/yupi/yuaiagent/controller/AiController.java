package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.app.LoveApp;
import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private YuManus yuManus;

    /**
     * 同步调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(180000L); // 3 分钟超时
        // 获取 Flux 响应式数据流并且直接通过订阅推送给 SseEmitter
        loveApp.doChatByStream(message, chatId)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                }, sseEmitter::completeWithError, sseEmitter::complete);
        // 返回
        return sseEmitter;
    }

    /**
     * 流式调用 Manus 超级智能体（支持记忆功能）
     *
     * @param message 用户消息
     * @param chatId 对话ID（可选，不传则自动生成）
     * @return SSE 流式响应
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId) {
        // 1. 生成或复用 chatId
        if (chatId == null || chatId.isEmpty()) {
            chatId = UUID.randomUUID().toString();
        }
        
        // 2. 从 UserContext 获取 userId（实际项目中应从登录态获取）
        String userId = UserContext.getUserId();
        
        // 3. 设置 YuManus 的对话参数（用于短期记忆和长期记忆）
        yuManus.setConversationParams(chatId, userId);
        
        // 4. 执行流式对话
        return yuManus.runStream(message);
    }

    /**
     * 测试接口：设置 userId 后调用 Manus
     * 使用方式：/ai/manus/chat/test?userId=test_user_001&message=你好
     *
     * @param userId 用户ID
     * @param message 用户消息
     * @param chatId 对话ID（可选）
     * @return SSE 流式响应
     */
    @GetMapping("/manus/chat/test")
    public SseEmitter doChatWithManusTest(String userId, String message, String chatId) {
        // 1. 设置 userId 到 UserContext
        if (userId != null && !userId.isEmpty()) {
            UserContext.setUserId(userId);
        }
        
        // 2. 生成或复用 chatId
        if (chatId == null || chatId.isEmpty()) {
            chatId = UUID.randomUUID().toString();
        }
        
        // 3. 设置 YuManus 的对话参数
        yuManus.setConversationParams(chatId, userId);
        
        // 4. 执行流式对话
        SseEmitter emitter = yuManus.runStream(message);
        
        // 5. 完成后清理 UserContext
        emitter.onCompletion(() -> UserContext.clear());
        emitter.onTimeout(() -> UserContext.clear());
        
        return emitter;
    }

    /**
     * 带记忆和RAG的 Manus 超级智能体调用
     * TODO: 如需支持 userId 隔离，请参考 LoveApp 实现，在运行时通过 .advisors(spec -> spec.param(...)) 传入
     *
     * @param message
     * @param chatId
     * @param userId
     * @return
     */
//    @GetMapping("/manus/chat/with-memory")
//    public String doChatWithManusWithMemory(String message, String chatId, String userId) {
//        // YuManus 已通过 defaultAdvisors 集成记忆功能，userId 由 UserContext 自动获取
//        return yuManus.run(message);
//    }

    /**
     * 带记忆和RAG的 Manus 超级智能体流式调用
     * TODO: 如需支持 userId 隔离，请参考 LoveApp 实现
     *
     * @param message
     * @param chatId
     * @param userId
     * @return
     */
//    @GetMapping("/manus/chat/with-memory/sse")
//    public SseEmitter doChatWithManusWithMemorySSE(String message, String chatId, String userId) {
//        return yuManus.runStream(message);
//    }
}
