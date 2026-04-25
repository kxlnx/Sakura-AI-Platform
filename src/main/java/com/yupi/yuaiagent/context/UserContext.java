package com.yupi.yuaiagent.context;

/**
 * 用户上下文管理器
 * 使用 ThreadLocal 存储当前登录用户信息
 * 在 Web 应用中，登录时设置当前用户，后续调用自动获取
 */
public class UserContext {

    private static final ThreadLocal<String> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>();

    public static void setUserId(String userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static String getUserId() {
        String userId = USER_ID_HOLDER.get();
        return userId != null ? userId : "anonymous";
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID_HOLDER.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID_HOLDER.get();
    }

    public static void clear() {
        USER_ID_HOLDER.remove();
        SESSION_ID_HOLDER.remove();
    }

    public static String getMemoryKey(String chatId) {
        return getUserId() + ":" + chatId;
    }
}