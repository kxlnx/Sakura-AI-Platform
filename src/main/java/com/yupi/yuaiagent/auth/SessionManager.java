package com.yupi.yuaiagent.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yupi.yuaiagent.entity.SysSession;
import com.yupi.yuaiagent.mapper.SysSessionMapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis + MySQL 旁路缓存 Session 管理
 * token → userId 映射，30 分钟过期
 * 读：Redis → MySQL → 重建 Redis
 * 写：MySQL + Redis 双写
 */
@Component
public class SessionManager {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SysSessionMapper sysSessionMapper;

    private static final String SESSION_PREFIX = "session:";
    private static final long SESSION_TTL = 30; // 分钟

    /** 登录成功 → 双写 MySQL + Redis，返回 token */
    public String createSession(String userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        // 1. MySQL 持久化
        SysSession session = new SysSession();
        session.setToken(token);
        session.setUserId(userId);
        session.setCreateTime(now);
        session.setExpireTime(now.plusMinutes(SESSION_TTL));
        sysSessionMapper.insert(session);

        // 2. Redis 缓存
        stringRedisTemplate.opsForValue().set(
                SESSION_PREFIX + token, userId,
                SESSION_TTL, TimeUnit.MINUTES);

        return token;
    }

    /** 从 token 拿 userId：Redis → MySQL → 重建 Redis */
    public String getUserId(String token) {
        if (token == null) return null;

        // 1. 查 Redis
        String userId = stringRedisTemplate.opsForValue().get(SESSION_PREFIX + token);
        if (userId != null) {
            stringRedisTemplate.expire(SESSION_PREFIX + token, SESSION_TTL, TimeUnit.MINUTES);
            return userId;
        }

        // 2. Redis 未命中 → 查 MySQL
        SysSession session = sysSessionMapper.selectOne(
                new LambdaQueryWrapper<SysSession>()
                        .eq(SysSession::getToken, token));
        if (session == null) return null;

        // 3. 检查过期
        if (session.getExpireTime() != null
                && session.getExpireTime().isBefore(LocalDateTime.now())) {
            sysSessionMapper.deleteById(session.getId());
            return null;
        }

        // 4. 重建 Redis 缓存
        userId = session.getUserId();
        stringRedisTemplate.opsForValue().set(
                SESSION_PREFIX + token, userId,
                SESSION_TTL, TimeUnit.MINUTES);

        return userId;
    }

    /** 登出 → 双删 */
    public void removeSession(String token) {
        if (token != null) {
            stringRedisTemplate.delete(SESSION_PREFIX + token);
            sysSessionMapper.delete(
                    new LambdaQueryWrapper<SysSession>()
                            .eq(SysSession::getToken, token));
        }
    }
}
