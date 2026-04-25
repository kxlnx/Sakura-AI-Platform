package com.yupi.yuaiagent.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yupi.yuaiagent.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天消息数据访问层 Mapper
 * 继承 BaseMapper 后，自带强大的单表 CRUD 能力
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

}