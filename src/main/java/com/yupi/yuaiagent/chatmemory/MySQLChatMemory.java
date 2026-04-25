package com.yupi.yuaiagent.chatmemory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yupi.yuaiagent.constant.RedisKeyConstant;
import com.yupi.yuaiagent.entity.ChatMessage;
import com.yupi.yuaiagent.service.ChatMessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 优化后的基于 MySQL + Redis 的聊天记忆实现
 * 1. 支持增量缓存更新（高性能）
 * 2. 支持自动摘要压缩（长效记忆）
 * 3. 缓存自愈机制（高可用）
 */

//@Component
@Slf4j
public class MySQLChatMemory implements ChatMemory {

    @Resource
    private ChatMessageService chatMessageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "dashscopeChatModel")
    private ChatModel chatModel;

    @Value("${yu-ai.memory.safe-limit:100}")
    private int safeLimit;

    /**
     * 触发摘要压缩的阈值（建议设为 safeLimit 的 80%）
     */
    private int SUMMARY_THRESHOLD = safeLimit;

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (CollUtil.isEmpty(messages)) return;

        // 1. 持久化到 MySQL
        List<ChatMessage> newEntities = messages.stream()
                .map(message -> ChatMessage.fromMessage(conversationId, message))
                .toList();
        newEntities.forEach(chatMessageService::save);

        // 2. 【优化点】只有当存入的消息包含 AI 的回复时，才触发摘要检查
        // 这样用户消息阶段就不会触发，避免一轮对话压缩两次
        boolean hasAssistantMessage = messages.stream()
                .anyMatch(m -> m.getMessageType() == MessageType.ASSISTANT);

        boolean summarized = false;
        if (hasAssistantMessage) {
            summarized = checkAndSummarize(conversationId);
        }

        if (summarized) {
            refreshCache(conversationId);
        } else {
            incrementalUpdateRedis(conversationId, newEntities);
        }
    }
    @Override
    public List<Message> get(String conversationId) {
        String cacheKey = RedisKeyConstant.getMemoryKey(conversationId);

        // 1. 尝试从 Redis 读取
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cachedJson)) {
            log.info("[Redis-Cache] ★ 命中缓存, chatId: {}", conversationId);
            List<ChatMessage> cachedList = JSONUtil.toList(cachedJson, ChatMessage.class);
            return convertToSpringAiMessages(cachedList);
        }

        // 2. 缓存未命中（过期或首次加载），从 MySQL 捞取
        log.info("[Redis-Cache] 缓存未命中，从 MySQL 加载, chatId: {}", conversationId);
        List<ChatMessage> chatMessages = chatMessageService.findLatestMessages(conversationId, safeLimit);

        // 3. 缓存自愈：回写 Redis
        if (CollUtil.isNotEmpty(chatMessages)) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(chatMessages), 30, TimeUnit.MINUTES);
        }

        return convertToSpringAiMessages(chatMessages);
    }

    /**
     * 核心逻辑：检查并生成摘要
     * @return true 表示发生了压缩更新
     */
    private boolean checkAndSummarize(String conversationId) {
        long count = chatMessageService.count(new QueryWrapper<ChatMessage>().eq("conversationId", conversationId));
        if (count < SUMMARY_THRESHOLD) return false;

        log.info("[Summary] 触发摘要压缩，当前消息数: {}", count);

        // 1. 取出最老的 10 条消息进行总结
        List<ChatMessage> oldMessages = chatMessageService.list(new QueryWrapper<ChatMessage>()
                .eq("conversationId", conversationId)
                .orderByAsc("id")
                .last("limit 10"));

        String historyText = oldMessages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

        // 2. 调用 AI 总结
        String prompt = "请总结以下对话历史（50字以内），包含人物关系和关键结论：\n" + historyText;
        String summary = chatModel.call(prompt);

        // 3. 数据库操作：删除旧明细，插入摘要实体
        chatMessageService.removeByIds(oldMessages.stream().map(ChatMessage::getId).toList());

        ChatMessage summaryEntity = new ChatMessage();
        summaryEntity.setConversationId(conversationId);
        summaryEntity.setRole("system"); // 摘要作为系统级上下文
        summaryEntity.setContent("[历史记忆摘要]: " + summary);
        summaryEntity.setCreateTime(new Date());
        chatMessageService.save(summaryEntity);

        return true;
    }

    /**
     * 高性能增量更新：直接操作内存中的 List 副本
     */
    private void incrementalUpdateRedis(String conversationId, List<ChatMessage> newMessages) {
        String cacheKey = RedisKeyConstant.getMemoryKey(conversationId);
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);

        List<ChatMessage> messageList;
        if (StrUtil.isNotBlank(cachedJson)) {
            messageList = JSONUtil.toList(cachedJson, ChatMessage.class);
            messageList.addAll(newMessages);
            // 维持滑动窗口大小
            if (messageList.size() > safeLimit) {
                messageList = messageList.subList(messageList.size() - safeLimit, messageList.size());
            }
        } else {
            // 缓存不存在则全量拉取一次
            messageList = chatMessageService.findLatestMessages(conversationId, safeLimit);
        }

        if (CollUtil.isNotEmpty(messageList)) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(messageList), 30, TimeUnit.MINUTES);
            log.info("[Redis-Cache] 增量更新成功, chatId: {}", conversationId);
        }
    }

    private void refreshCache(String conversationId) {
        List<ChatMessage> latest = chatMessageService.findLatestMessages(conversationId, safeLimit);
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.getMemoryKey(conversationId), JSONUtil.toJsonStr(latest), 30, TimeUnit.MINUTES);
        log.info("[Redis-Cache] 全量刷新完成（摘要后同步）, chatId: {}", conversationId);
    }

    @Override
    public void add(String conversationId, Message message) {
        add(conversationId, Collections.singletonList(message));
    }

    @Override
    public void clear(String conversationId) {
        chatMessageService.deleteByConversationId(conversationId);
        stringRedisTemplate.delete(RedisKeyConstant.getMemoryKey(conversationId));
    }

    private List<Message> convertToSpringAiMessages(List<ChatMessage> chatMessages) {
        if (chatMessages == null) return Collections.emptyList();
        return chatMessages.stream().map(ChatMessage::toMessage).collect(Collectors.toList());
    }
}