package com.yupi.yuaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.yupi.yuaiagent.enums.MessageTypeEnum;

import lombok.Data;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.Serializable;
import java.util.Date;

/**
 * 聊天消息表
 * @TableName chat_message
 */
@TableName(value ="chat_message")
@Data
public class ChatMessage implements Serializable {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 角色：user/assistant/system
     */
    private String role;

    /**
     * 消息token数
     */
    private Integer tokens;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    private Integer isDeleted;


    /**
     * 将Spring AI的Message对象转换为数据库实体
     */
    public static ChatMessage fromMessage(String conversationId, Message message) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setConversationId(conversationId);
        // 从 conversationId（格式: userId:chatId）提取 userId
        int colonIdx = conversationId.indexOf(':');
        if (colonIdx > 0) {
            chatMessage.setUserId(conversationId.substring(0, colonIdx));
        }
        // 根据消息类型获取内容
        if (message instanceof SystemMessage) {
            chatMessage.setContent(((SystemMessage) message).getText());
        } else if (message instanceof UserMessage) {
            chatMessage.setContent(((UserMessage) message).getText());
        } else if (message instanceof AssistantMessage) {
            chatMessage.setContent(((AssistantMessage) message).getText());
        }
        chatMessage.setRole(message.getMessageType().getValue());
        Object promptTokens = message.getMetadata().get("promptTokens");
        Object completionTokens = message.getMetadata().get("completionTokens");
        int total = 0;
        if (promptTokens != null) total += (Integer) promptTokens;
        if (completionTokens != null) total += (Integer) completionTokens;
        if (total > 0) chatMessage.setTokens(total);
        chatMessage.setCreateTime(new Date());
        chatMessage.setUpdateTime(new Date());
        chatMessage.setIsDeleted(0);
        return chatMessage;
    }

    /**
     * 将数据库实体转换为Spring AI的Message对象
     */
    public Message toMessage() {
        MessageTypeEnum type = MessageTypeEnum.fromString(role);
        return switch (type) {
            case SYSTEM -> new SystemMessage(content);
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            default -> throw new IllegalArgumentException("Unknown message role: " + role);
        };
    }

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
