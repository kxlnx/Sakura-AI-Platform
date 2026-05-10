package com.yupi.yuaiagent.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedisKeyUtils {

    // 自动获取 application.yml 中的应用名
    @Value("${spring.application.name:sakura-agent}")
    private String appName;

    // 自动获取当前激活的环境 (local/prod)
    @Value("${spring.profiles.active:default}")
    private String env;

    /**
     * 生成聊天记忆的 Key
     * 格式示例：sakura-agent:local:memory:chatId_123
     */
    public String getChatMemoryKey(String chatId) {
        return String.format("%s:%s:memory:%s", appName, env, chatId);
    }

    /**
     * 生成分布式锁的 Key
     */
    public String getChatLockKey(String chatId) {
        return String.format("%s:%s:lock:%s", appName, env, chatId);
    }
}