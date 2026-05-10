package com.yupi.yuaiagent.chatmemory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@Primary
public class RedisListChatMemory implements ChatMemory {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "dashscopeChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${sakura.memory.safe-limit:100}")
    private int safeLimit;

    private static final String MEMORY_KEY_PREFIX = "chat:memory:";

    private static final int MAX_CHAR_LIMIT = 32000;

    @Override
    public void add(String conversationId, List<Message> newMessages) {
        String redisKey = buildRedisKey(conversationId);
        
        log.info("[短期记忆-写] conversationId={}, redisKey={}, 消息数量={}", conversationId, redisKey, newMessages.size());
        for (int i = 0; i < newMessages.size(); i++) {
            Message msg = newMessages.get(i);
            log.info("[短期记忆-写] 消息{}: {} = {}", i, msg.getMessageType(), msg.getText());
        }

        String roundJson = serializeRound(newMessages);
        stringRedisTemplate.opsForList().rightPush(redisKey, roundJson);
        stringRedisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
        
        log.info("[短期记忆-写] Redis 写入成功, key={}", redisKey);

        checkAndCompress(redisKey);
    }

    @Override
    public List<Message> get(String conversationId) {
        String redisKey = buildRedisKey(conversationId);
        
        log.info("[短期记忆-读] conversationId={}, redisKey={}", conversationId, redisKey);

        List<String> rawRounds = stringRedisTemplate.opsForList().range(redisKey, 0, -1);
        if (rawRounds == null || rawRounds.isEmpty()) {
            log.info("[短期记忆-读] Redis 中无历史数据");
            return new ArrayList<>();
        }
        
        log.info("[短期记忆-读] Redis 召回 {} 轮对话", rawRounds.size());

        List<Message> allMessages = new ArrayList<>();
        for (String rawRound : rawRounds) {
            allMessages.addAll(deserializeRound(rawRound));
        }

        int fromIndex = Math.max(0, allMessages.size() - safeLimit);
        List<Message> result = allMessages.subList(fromIndex, allMessages.size());
        
        log.info("[短期记忆-读] 返回 {} 条消息（总消息数={}, safeLimit={}）", result.size(), allMessages.size(), safeLimit);
        for (int i = 0; i < result.size(); i++) {
            Message msg = result.get(i);
            log.info("[短期记忆-读] 消息{}: {} = {}", i, msg.getMessageType(), msg.getText());
        }
        
        return result;
    }

    @Override
    public void clear(String conversationId) {
        String redisKey = buildRedisKey(conversationId);
        stringRedisTemplate.delete(redisKey);
    }

    private String buildRedisKey(String conversationId) {
        // conversationId 已经是 userId:chatId 的格式，直接拼接即可
        return MEMORY_KEY_PREFIX + conversationId;
    }

    /**
     * 核心逻辑：超过 32k 字符，触发前一半做摘要
     */
    private void checkAndCompress(String redisKey) {
        List<String> rawRounds = stringRedisTemplate.opsForList().range(redisKey, 0, -1);
        if (rawRounds == null || rawRounds.size() <= 1) {
            return; // 只有一条没必要压缩
        }

        // 1. 计算当前 List 里的总文字量
        long totalChars = 0;
        for (String rawRound : rawRounds) {
            totalChars += rawRound.length(); // 粗略计算 JSON 字符串长度，工程上最快
        }
        // 2. 判断是否超标
        if (totalChars > MAX_CHAR_LIMIT) {
            log.info("[记忆压缩] 触发阈值！当前字数 {} > {}。执行减半压缩...", totalChars, MAX_CHAR_LIMIT);

            int halfIndex = rawRounds.size() / 2;

            // 切割：前一半(待摘要)，后一半(原样保留)
            List<String> toSummarizeRounds = rawRounds.subList(0, halfIndex);
            List<String> toKeepRounds = rawRounds.subList(halfIndex, rawRounds.size());

            // 让大模型对前一半进行总结
            String summary = doSummarize(toSummarizeRounds);

            // ====== 开始更新 Redis ======
            stringRedisTemplate.delete(redisKey);

            // 第一条放回摘要 (包装成一个 SystemMessage 轮次)
            List<Message> summaryRound = List.of(new SystemMessage("【前情提要】: " + summary));
            stringRedisTemplate.opsForList().rightPush(redisKey, serializeRound(summaryRound));

            // 后续把窗口后一半的数据原样 push 回去
            for (String keepRound : toKeepRounds) {
                stringRedisTemplate.opsForList().rightPush(redisKey, keepRound);
            }
            stringRedisTemplate.expire(redisKey, 7, TimeUnit.DAYS);

            log.info("[记忆压缩] 压缩完成！已放回摘要文本与后半部分窗口数据。");
        }
    }

    /**
     * 调用 LLM 生成摘要
     */
    private String doSummarize(List<String> toSummarizeRounds) {
        StringBuilder sb = new StringBuilder("请将以下较早的历史对话进行高度浓缩的摘要总结，提取用户的核心事实和意图：\n\n");
        for (String round : toSummarizeRounds) {
            List<Message> msgs = deserializeRound(round);
            for (Message msg : msgs) {
                sb.append(msg.getMessageType().name()).append(": ").append(msg.getText()).append("\n");
            }
        }
        // 阻塞调用大模型
        return chatModel.call(sb.toString());
    }

    // ==========================================
    // 纯净的序列化与反序列化工具 (避开复杂的多态嵌套问题)
    // ==========================================
    private String serializeRound(List<Message> messages) {
        try {
            List<Map<String, String>> list = messages.stream()
                    .map(msg -> Map.of("type", msg.getMessageType().name(), "content", msg.getText()))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.error("序列化失败", e);
            return "[]";
        }
    }

    private List<Message> deserializeRound(String json) {
        try {
            List<Map<String, String>> list = objectMapper.readValue(json, new TypeReference<>() {});
            return list.stream().map(map -> {
                String type = map.get("type");
                String content = map.get("content");
                if ("USER".equals(type)) return new UserMessage(content);
                if ("ASSISTANT".equals(type)) return new AssistantMessage(content);
                return new SystemMessage(content);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}