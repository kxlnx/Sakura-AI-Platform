package com.yupi.yuaiagent.memory;

import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 长期记忆增强顾问
 * 负责在对话前读取长期记忆，并在对话后将重要信息写入长期记忆
 */
@Slf4j
@Component
public class LongTermMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    @Resource
    private LongTermMemoryReader longTermMemoryReader;

    @Resource
    private LongTermMemoryWriter longTermMemoryWriter;

    @Resource
    @Qualifier("backgroundExecutor")
    private Executor backgroundExecutor;

    private static final String USER_ID_PARAM = "userId";

    @Value("${sakura.memory.truncate-length:100}")
    private int messageTruncateLength;

    @Value("${sakura.memory.recall-top-k:3}")
    private int memoryRecallTopK;

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return -100; // 必须在 RAG Advisor 之前执行，否则 RAG 的空上下文兜底会替换用户消息
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest enhancedRequest = handleLongTermMemory(request);
        return chain.nextCall(enhancedRequest);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest enhancedRequest = handleLongTermMemory(request);
        Flux<ChatClientResponse> responseFlux = chain.nextStream(enhancedRequest);
        return responseFlux;
    }

    /**
     * 处理长期记忆的读取和注入
     */
    private ChatClientRequest handleLongTermMemory(ChatClientRequest request) {
        try {
            String userId = extractUserId(request);

            String userMessage = extractUserMessage(request);
            if (userMessage == null || userMessage.isEmpty()) {
                log.warn("[长期记忆] 无法提取用户消息");
                return request;
            }

            log.info("[长期记忆] userId={}, 用户消息: {}", userId, userMessage);

            handleMemoryWrite(userId, userMessage);

            List<String> memoryContents = handleMemoryRead(userId, userMessage);
            if (!memoryContents.isEmpty()) {
                log.info("[长期记忆] 召回 {} 条记忆", memoryContents.size());
                // 将长期记忆注入到系统消息中
                String memorySystemMessage = buildMemorySystemMessage(memoryContents);
                SystemMessage systemMessage = new SystemMessage(memorySystemMessage);
                
                // 创建新的Prompt，将系统消息添加到前面
                List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
                messages.add(0, systemMessage); // 在最前面添加记忆系统消息
                org.springframework.ai.chat.prompt.Prompt newPrompt = new org.springframework.ai.chat.prompt.Prompt(messages, request.prompt().getOptions());
                
                return new ChatClientRequest(newPrompt, request.context());
            }

        } catch (Exception e) {
            log.error("[长期记忆] 处理失败", e);
        }

        return request;
    }

    /**
     * 从请求中提取用户ID
     */
    private String extractUserId(ChatClientRequest request) {
        // 方案1：优先从 UserContext 获取（测试环境或登录后的用户）
        String userIdFromContext = UserContext.getUserId();
        if (userIdFromContext != null && !"anonymous".equals(userIdFromContext)) {
            log.debug("[长期记忆] 从 UserContext 获取 userId: {}", userIdFromContext);
            return userIdFromContext;
        }
        
        // 方案2：从 request context 中获取
        Map<String, Object> params = request.context();
        if (params != null && params.containsKey(USER_ID_PARAM)) {
            Object userId = params.get(USER_ID_PARAM);
            if (userId != null && !userId.toString().isEmpty()) {
                log.debug("[长期记忆] 从 request context 获取 userId: {}", userId);
                return userId.toString();
            }
        }
        
        // 方案3：默认值
        log.warn("[长期记忆] 未找到 userId，使用默认值 default_user");
        return "default_user";
    }

    /**
     * 从请求中提取用户消息（只提取最后一条用户消息，避免日志过长）
     */
    private String extractUserMessage(ChatClientRequest request) {
        try {
            var userMessages = request.prompt().getUserMessages();
            if (userMessages != null && !userMessages.isEmpty()) {
                UserMessage lastUserMessage = userMessages.get(userMessages.size() - 1);
                String content = lastUserMessage.getText();
                if (content.length() > messageTruncateLength) {
                    return content.substring(0, messageTruncateLength) + "...";
                }
                return content;
            }
        } catch (Exception e) {
            log.warn("[长期记忆] 提取用户消息失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 处理记忆写入
     */
    private void handleMemoryWrite(String userId, String userMessage) {
        if (userMessage.contains("记住") || userMessage.contains("请记住")) {
            log.info("[长期记忆] 检测到显式记忆指令");
            CompletableFuture.runAsync(
                    () -> longTermMemoryWriter.handleExplicitMemory(userId, userMessage),
                    backgroundExecutor);
        } else if (shouldExtractImplicitMemory(userMessage)) {
            log.info("[长期记忆] 检测到可提取的隐式信息");
            CompletableFuture.runAsync(
                    () -> longTermMemoryWriter.handleImplicitMemory(userId, userMessage),
                    backgroundExecutor);
        }
    }

    /**
     * 判断是否应该提取隐式记忆
     */
    private boolean shouldExtractImplicitMemory(String message) {
        String[] keywords = {"我是", "我叫", "我的", "我在", "我喜欢", "我不喜欢",
                              "我住在", "我今年", "我从事", "我的工作", "我的名字", "我是一名"};
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                log.info("[长期记忆] 隐式提取触发！关键词={}, 消息={}", keyword, message);
                return true;
            }
        }
        log.debug("[长期记忆] 隐式提取未触发，消息={}", message);
        return false;
    }

    /**
     * 处理记忆读取
     */
    private List<String> handleMemoryRead(String userId, String userMessage) {
        List<String> memoryContents = new ArrayList<>();

        // 检查是否需要触发长期记忆
        boolean shouldTrigger = longTermMemoryReader.shouldTrigger(userMessage);

        // 读取长期记忆
        var memories = longTermMemoryReader.readMemory(userId, userMessage, memoryRecallTopK);
        if (!memories.isEmpty()) {
            log.info("[长期记忆] 召回 {} 条记忆", memories.size());
            // 暂时注释掉详细日志，避免日志过多
            // for (var memory : memories) {
            //     memoryContents.add(memory.getText());
            //     log.debug("[长期记忆] 记忆内容: {}", memory.getText());
            // }
            for (var memory : memories) {
                memoryContents.add(memory.getText());
            }
        }

        return memoryContents;
    }

    /**
     * 构建包含长期记忆的系统消息
     */
    private String buildMemorySystemMessage(List<String> memoryContents) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户历史偏好记录】\n");
        sb.append("之前对话中了解到的用户历史兴趣和关注点：\n");
        for (int i = 0; i < memoryContents.size(); i++) {
            sb.append((i + 1)).append(". ").append(memoryContents.get(i)).append("\n");
        }
        sb.append("\n结合以上用户偏好，提供更有针对性的历史解答。");
        return sb.toString();
    }
}
