package com.yupi.yuaiagent.constant;

/**
 * 严格的 Redis Key 规范管理
 */
public interface RedisKeyConstant {

    /**
     * 业务根前缀（手动锁定项目名）
     */
    String ROOT = "yu_ai";

    /**
     * 模块名
     */
    String MODULE_CHAT = "chat";

    /**
     * 数据类型标识
     */
    String TYPE_MEMORY = "memory";
    String TYPE_LOCK = "lock";

    /**
     * 拼接方法：yu_ai:chat:memory:{id}
     */
    static String getMemoryKey(String id) {
        return String.join(":", ROOT, MODULE_CHAT, TYPE_MEMORY, id);
    }

    /**
     * 拼接方法：yu_ai:chat:lock:{id}
     */
    static String getLockKey(String id) {
        return String.join(":", ROOT, MODULE_CHAT, TYPE_LOCK, id);
    }
}