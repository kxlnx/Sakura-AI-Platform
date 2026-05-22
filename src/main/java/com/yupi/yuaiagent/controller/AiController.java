package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.app.LoveApp;
import com.yupi.yuaiagent.constant.RedisKeyConstant;
import com.yupi.yuaiagent.context.UserContext;
import com.yupi.yuaiagent.service.ChatMessageService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final String AGENT_CHAT_PREFIX = "agent_chat_";
    private static final int TITLE_TRUNCATE_LENGTH = 20;
    private static final int HISTORY_LIMIT = 50;

    @Resource
    private LoveApp loveApp;

    @Resource
    private ObjectProvider<YuManus> yuManusProvider;

    @Resource
    private ChatMessageService chatMessageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String resolveUserId(String userId) {
        if (userId == null || userId.isEmpty()) {
            return UserContext.getUserId();
        }
        return userId;
    }

    @GetMapping("/conversations/delete")
    public Map<String, Object> deleteConversation(String chatId, String userId) {
        userId = resolveUserId(userId);
        String conversationId = userId + ":" + chatId;
        chatMessageService.deleteByConversationId(conversationId);
        String redisKey = RedisKeyConstant.getMemoryKey(conversationId);
        stringRedisTemplate.delete(redisKey);
        stringRedisTemplate.delete("chat:memory:" + conversationId);
        return Map.of("code", 200, "msg", "已删除");
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> getMessages(String chatId, String conversationId, String userId) {
        String cid;
        if (conversationId != null && !conversationId.isEmpty()) {
            cid = conversationId;
        } else {
            cid = resolveUserId(userId) + ":" + chatId;
        }
        return chatMessageService.findLatestMessages(cid, HISTORY_LIMIT).stream()
                .map(m -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("role", m.getRole());
                    map.put("content", m.getContent());
                    return map;
                }).toList();
    }

    @GetMapping("/conversations")
    public List<Map<String, String>> getConversations(String userId) {
        userId = resolveUserId(userId);
        var result = chatMessageService.getUserConversations(userId).stream()
                .map(m -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("conversationId", m.getConversationId());
                    String content = m.getContent();
                    map.put("title", content != null && content.length() > TITLE_TRUNCATE_LENGTH
                            ? content.substring(0, TITLE_TRUNCATE_LENGTH) + "..." : content);
                    map.put("time", m.getCreateTime().toString());
                    int idx = m.getConversationId().indexOf(':');
                    map.put("chatId", idx > 0 ? m.getConversationId().substring(idx + 1) : m.getConversationId());
                    return map;
                }).toList();
        return result;
    }

    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId, String userId) {
        return loveApp.doChat(message, chatId, resolveUserId(userId));
    }

    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId, String userId) {
        return loveApp.doChatByStream(message, chatId, resolveUserId(userId));
    }

    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String userId) {
        userId = resolveUserId(userId);
        String chatId = AGENT_CHAT_PREFIX + userId;
        YuManus yuManus = yuManusProvider.getObject();
        yuManus.setConversationParams(chatId, userId);
        return yuManus.runStream(message);
    }

}
