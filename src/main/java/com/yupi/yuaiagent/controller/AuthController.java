package com.yupi.yuaiagent.controller;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yupi.yuaiagent.auth.SessionManager;
import com.yupi.yuaiagent.entity.SysUser;
import com.yupi.yuaiagent.mapper.SysUserMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SessionManager sessionManager;

    /** 注册 */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.length() < 2
                || password == null || password.length() < 4) {
            return Map.of("code", 400, "msg", "用户名至少2位，密码至少4位");
        }
        // 查重
        Long count = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (count > 0) {
            return Map.of("code", 400, "msg", "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password));
        user.setNickname(username);
        sysUserMapper.insert(user);

        String token = sessionManager.createSession(String.valueOf(user.getId()));
        return Map.of("code", 200, "msg", "注册成功",
                "token", token, "userId", String.valueOf(user.getId()), "username", username);
    }

    /** 登录 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null || !BCrypt.checkpw(password, user.getPassword())) {
            return Map.of("code", 401, "msg", "用户名或密码错误");
        }

        String token = sessionManager.createSession(String.valueOf(user.getId()));
        return Map.of("code", 200, "msg", "登录成功",
                "token", token, "userId", String.valueOf(user.getId()), "username", user.getUsername());
    }

    /** 登出 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "token", required = false) String token) {
        sessionManager.removeSession(token);
        return Map.of("code", 200, "msg", "已登出");
    }
}
