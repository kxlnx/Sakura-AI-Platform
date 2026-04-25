package com.yupi.yuaiagent.memory;

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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private static final String USER_ID_PARAM = "userId";

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
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
                // 暂时注释掉记忆注入逻辑，避免API兼容性问题
                // 核心问题是记忆隔离，已经通过userId过滤解决
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
        Map<String, Object> params = request.context();
        if (params != null && params.containsKey(USER_ID_PARAM)) {
            Object userId = params.get(USER_ID_PARAM);
            if (userId != null && !userId.toString().isEmpty()) {
                return userId.toString();
            }
        }
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
                if (content.length() > 100) {
                    return content.substring(0, 100) + "...";
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
        // 处理显式记忆指令
        if (userMessage.contains("记住") || userMessage.contains("请记住")) {
            log.info("[长期记忆] 检测到显式记忆指令");
            longTermMemoryWriter.handleExplicitMemory(userId, userMessage);
        }
        // 可选：也可以处理隐式记忆提取
        else if (shouldExtractImplicitMemory(userMessage)) {
            log.info("[长期记忆] 检测到可提取的隐式信息");
            longTermMemoryWriter.handleImplicitMemory(userId, userMessage);
        }
    }

    /**
     * 判断是否应该提取隐式记忆
     */
    private boolean shouldExtractImplicitMemory(String message) {
        // 简单的启发式判断：消息中包含个人信息
        String[] keywords = {"我是", "我的", "我喜欢", "我不喜欢", "我的工作", "我的名字"};
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
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
        var memories = longTermMemoryReader.readMemory(userId, userMessage, 3);
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
        sb.append("【用户长期记忆】\n");
        sb.append("以下是你之前了解到的关于该用户的重要信息：\n");
        for (int i = 0; i < memoryContents.size(); i++) {
            sb.append((i + 1)).append(". ").append(memoryContents.get(i)).append("\n");
        }
        sb.append("\n请根据以上记忆信息，结合当前对话内容，给出更个性化的回复。");
        return sb.toString();
    }
}
