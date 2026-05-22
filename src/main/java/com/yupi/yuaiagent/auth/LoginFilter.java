package com.yupi.yuaiagent.auth;

import com.yupi.yuaiagent.chatmemory.MySQLChatMemory;
import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 登录过滤器：从请求头 token 解析 userId，注入 UserContext
 * 未登录用户也可访问，userId 为 anonymous
 */
@Component
public class LoginFilter implements Filter {

    @Resource
    private SessionManager sessionManager;

    // 无需认证的路径
    private static final String[] WHITELIST = {
            "/api/auth/", "/swagger", "/v3/api-docs", "/actuator"
    };

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String path = request.getRequestURI();
        String token = request.getHeader("token");

        // token 参数兜底（SSE 不支持自定义 header）
        if (token == null) {
            token = request.getParameter("token");
        }

        String userId = null;
        if (token != null) {
            userId = sessionManager.getUserId(token);
        }

        if (userId != null) {
            UserContext.setUserId(userId);
        } else {
            UserContext.setUserId("anonymous");
        }

        try {
            chain.doFilter(req, resp);
        } finally {
            UserContext.clear();
            MySQLChatMemory.clearRequestCache();
        }
    }
}
