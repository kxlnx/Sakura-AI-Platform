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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
@Component
@Primary
@Slf4j
public class MySQLChatMemory implements ChatMemory {

    // ======================== 依赖注入 ========================

    @Resource
    private ChatMessageService chatMessageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "dashscopeChatModel")
    private ChatModel chatModel;

    @Resource
    @Qualifier("backgroundExecutor")
    private Executor backgroundExecutor;

    // ======================== 配置 ========================

    @Value("${sakura.session.ttl-minutes:30}")
    private int redisTtlMinutes;

    @Value("${sakura.summary.batch-size:10}")
    private int summaryBatchSize;

    @Value("${sakura.memory.safe-limit:100}")
    private int safeLimit;

    @Value("${sakura.memory.summary-threshold:20}")
    private int summaryThreshold;

    // ======================== 请求级缓存 ========================

    /** 同一 HTTP 请求内，同一 chatId 只查一次 Redis/MySQL，后续调用直接内存返回 */
    private static final ThreadLocal<Map<String, List<Message>>> requestCache =
            ThreadLocal.withInitial(HashMap::new);

    /** LoginFilter 在请求结束时调用，防止内存泄漏 */
    public static void clearRequestCache() {
        Map<String, List<Message>> cache = requestCache.get();
        if (cache != null) cache.clear();
        requestCache.remove();
    }

    // ======================== 读：三层缓存 ========================

    @Override
    public List<Message> get(String conversationId) {
        // ① ThreadLocal 请求缓存
        Map<String, List<Message>> cache = requestCache.get();
        List<Message> cached = cache.get(conversationId);
        if (cached != null) {
            log.debug("[短期记忆] 请求级缓存命中, conversationId={}", conversationId);
            return cached;
        }

        String cacheKey = RedisKeyConstant.getMemoryKey(conversationId);

        // ② Redis 热缓存
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cachedJson)) {
            log.info("[短期记忆] Redis热缓存命中, conversationId= {}", conversationId);
            List<ChatMessage> cachedList = JSONUtil.toList(cachedJson, ChatMessage.class);
            List<Message> result = convertToSpringAiMessages(cachedList);
            cache.put(conversationId, result);
            return result;
        }

        // ③ MySQL 冷存储兜底 + 缓存自愈
        log.info("[短期记忆] Redis未命中，从MySQL加载, conversationId= {}", conversationId);
        List<ChatMessage> chatMessages = chatMessageService.findLatestMessages(conversationId, safeLimit);
        if (CollUtil.isNotEmpty(chatMessages)) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(chatMessages),
                    redisTtlMinutes, TimeUnit.MINUTES);
        }

        List<Message> result = convertToSpringAiMessages(chatMessages);
        cache.put(conversationId, result);
        return result;
    }

    // ======================== 写：双写 + 增量更新 ========================

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (CollUtil.isEmpty(messages)) return;

        // ① MySQL 持久化（同步）
        List<ChatMessage> newEntities = messages.stream()
                .map(message -> ChatMessage.fromMessage(conversationId, message))
                .toList();
        newEntities.forEach(chatMessageService::save);

        // ② 含 AI 回复 → 异步触发摘要压缩检查
        boolean hasAssistantMessage = messages.stream()
                .anyMatch(m -> m.getMessageType() == MessageType.ASSISTANT);
        if (hasAssistantMessage) {
            CompletableFuture.runAsync(() -> checkAndSummarize(conversationId), backgroundExecutor);
        }

        // ③ Redis 增量更新 + ThreadLocal 同步
        incrementalUpdateRedis(conversationId, newEntities);
    }

    @Override
    public void add(String conversationId, Message message) {
        add(conversationId, Collections.singletonList(message));
    }

    // ======================== 删 ========================

    @Override
    public void clear(String conversationId) {
        chatMessageService.deleteByConversationId(conversationId);
        stringRedisTemplate.delete(RedisKeyConstant.getMemoryKey(conversationId));
    }

    // ======================== 摘要压缩 ========================

    /**
     * 消息数超过 summary-threshold 时，取最老 N 条 → LLM 生成摘要 →
     * 删除原始消息 + 插入 [历史记忆摘要] 实体。
     * 异步执行，不阻塞请求线程。
     */
    private boolean checkAndSummarize(String conversationId) {
        long count = chatMessageService.count(
                new QueryWrapper<ChatMessage>().eq("conversationId", conversationId));
        if (count < summaryThreshold) return false;

        log.info("[短期记忆] 触发摘要压缩, conversationId={}, 消息数={}", conversationId, count);

        // 取最老的 N 条
        List<ChatMessage> oldMessages = chatMessageService.list(
                new QueryWrapper<ChatMessage>()
                        .eq("conversationId", conversationId)
                        .orderByAsc("id")
                        .last("limit " + summaryBatchSize));

        String historyText = oldMessages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

        // LLM 摘要
        String prompt = "请总结以下对话历史（50字以内），包含人物关系和关键结论：\n" + historyText;
        String summary = chatModel.call(prompt);

        // 替换旧消息为摘要
        chatMessageService.removeByIds(
                oldMessages.stream().map(ChatMessage::getId).toList());

        ChatMessage summaryEntity = new ChatMessage();
        summaryEntity.setConversationId(conversationId);
        int idx = conversationId.indexOf(':');
        if (idx > 0) summaryEntity.setUserId(conversationId.substring(0, idx));
        summaryEntity.setRole("system");
        summaryEntity.setContent("[历史记忆摘要]: " + summary);
        summaryEntity.setCreateTime(new Date());
        chatMessageService.save(summaryEntity);

        return true;
    }

    // ======================== Redis 增量更新 ========================

    /**
     * 从 Redis 取出已有消息列表 → 追加新消息 → 截断到 safeLimit → 写回 Redis + ThreadLocal。
     * 避免了每次全量从 MySQL 重建缓存的开销。
     */
    private void incrementalUpdateRedis(String conversationId, List<ChatMessage> newMessages) {
        String cacheKey = RedisKeyConstant.getMemoryKey(conversationId);
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);

        List<ChatMessage> messageList;
        if (StrUtil.isNotBlank(cachedJson)) {
            messageList = JSONUtil.toList(cachedJson, ChatMessage.class);
            messageList.addAll(newMessages);
            if (messageList.size() > safeLimit) {
                messageList = messageList.subList(
                        messageList.size() - safeLimit, messageList.size());
            }
        } else {
            messageList = chatMessageService.findLatestMessages(conversationId, safeLimit);
        }

        if (CollUtil.isNotEmpty(messageList)) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(messageList),
                    redisTtlMinutes, TimeUnit.MINUTES);
            requestCache.get().put(conversationId, convertToSpringAiMessages(messageList));
            log.info("[短期记忆] Redis增量更新成功, conversationId= {}", conversationId);
        }
    }

    // ======================== 工具方法 ========================

    private List<Message> convertToSpringAiMessages(List<ChatMessage> chatMessages) {
        if (chatMessages == null) return Collections.emptyList();
        return chatMessages.stream().map(ChatMessage::toMessage).collect(Collectors.toList());
    }
}
