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
//@Service  // 当前使用 Redis 存储，MySQL 未启用
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
        implements ChatMessageService{

    @Override
    public List<ChatMessage> findLatestMessages(String conversationId, int limit) {
        // 只根据conversationId查询，不涉及messageType
        return this.lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .last("limit " + limit)
                .list();
    }

    @Override
    public Boolean deleteByConversationId(String conversationId) {
        return this.remove(lambdaQuery().eq(ChatMessage::getConversationId,conversationId));
    }
}
