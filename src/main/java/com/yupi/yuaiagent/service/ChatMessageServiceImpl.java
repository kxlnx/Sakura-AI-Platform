package com.yupi.yuaiagent.service;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yuaiagent.entity.ChatMessage;
import com.yupi.yuaiagent.mapper.ChatMessageMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author fgh
 * @description 针对表【chat_message(聊天消息表)】的数据库操作Service实现
 * @createDate 2025-04-30 19:56:23
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
        implements ChatMessageService{

    @Override
    public List<ChatMessage> findLatestMessages(String conversationId, int limit) {
        List<ChatMessage> list = this.lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + Math.min(limit, 1000))
                .list();
        java.util.Collections.reverse(list);
        return list;
    }

    @Override
    public Boolean deleteByConversationId(String conversationId) {
        // 物理删除（绕过全局逻辑删除）
        return this.baseMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)) > 0;
    }

    @Override
    public List<ChatMessage> getUserConversations(String userId) {
        return this.lambdaQuery()
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getRole, "user")
                .orderByDesc(ChatMessage::getCreateTime)
                .list()
                .stream()
                .collect(java.util.LinkedHashMap<String, ChatMessage>::new,
                         (m, msg) -> m.putIfAbsent(msg.getConversationId(), msg),
                         (m1, m2) -> {})
                .values().stream()
                .toList();
    }
}
