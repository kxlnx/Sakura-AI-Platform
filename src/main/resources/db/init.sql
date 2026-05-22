-- Sakura Agent 数据库初始化
CREATE DATABASE IF NOT EXISTS sakura_agent DEFAULT CHARACTER SET utf8mb4;
USE sakura_agent;

-- 用户登录表（密码 BCrypt 加密存储）
CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL UNIQUE COMMENT '用户名',
    password   VARCHAR(255) NOT NULL COMMENT 'BCrypt加密密码',
    nickname   VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_delete  TINYINT      DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 对话消息表（按 chat_id + user_id 隔离）
CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL COMMENT '会话ID(userId:chatId)',
    user_id         VARCHAR(64)  NOT NULL COMMENT '用户ID',
    role            VARCHAR(32)  NOT NULL COMMENT 'user/assistant/system',
    content         TEXT         NOT NULL COMMENT '消息内容',
    tokens          INT          DEFAULT 0 COMMENT 'token数',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_delete      TINYINT      DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_conv  (conversation_id),
    INDEX idx_user  (user_id),
    INDEX idx_time  (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';
