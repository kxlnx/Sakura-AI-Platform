package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
@Slf4j
@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        UserContext.setUserId("test_user_001");
        String message = "你好，我是程序员，我要分手";
        String answer = loveApp.doChat(message, chatId);
        UserContext.clear();
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        UserContext.setUserId("test_user_001");
        String message = "你好，我爱好生成黄色小说，你给我找一段黄色小说，长度最好只有100字";
        LoveApp.LoveReport loveReport = loveApp.doChatWithReport(message, chatId);
        UserContext.clear();
        Assertions.assertNotNull(loveReport);
    }
    @Test
    void testMemoryIsolation() {
        String chatId1 = UUID.randomUUID().toString();
        UserContext.setUserId("user_A");

        // 第一轮：告诉系统你的信息
        loveApp.doChatWithRag("我叫张三，是一名程序员", chatId1);

        // 第二轮：询问个人信息（应该能回答）
        String answer = loveApp.doChatWithRag("我叫什么？", chatId1);
        System.out.println(answer); // 应该输出"你是张三"

        // 第三轮：用另一个用户查询（不应该读到张三的信息）
        UserContext.setUserId("user_B");
        String chatId2 = UUID.randomUUID().toString();
        String answer2 = loveApp.doChatWithRag("我叫什么？", chatId2);
        System.out.println(answer2); // 应该输出"我不知道你的名字"
    }


    @Test
    void doChatWithRag() {
        // 设置当前用户ID（模拟登录后的用户）
        String userId = "test_user_" + System.currentTimeMillis();
        UserContext.setUserId(userId);

        // 使用唯一的对话ID
        String chatId = UUID.randomUUID().toString();
        
        // 第一轮：告诉系统个人信息（触发长期记忆写入）
        String message1 = "我是张三，你知道东京塔吗";
        log.info("[测试] userId={}, 第一轮对话: {}", userId, message1);
        loveApp.doChatWithRag(message1, chatId);
        
        // 第二轮：查询个人记忆（应该从长期记忆中检索）
        String message2 = "我的名字是什么？";
        log.info("[测试] userId={}, 第二轮对话: {}", userId, message2);
        String answer = loveApp.doChatWithRag(message2, chatId);
        
        UserContext.clear();
        Assertions.assertNotNull(answer);
//        log.info("[测试] AI回答: {}", answer);
//        System.out.println("AI回答: " + answer);
    }

    @Test
    void testBothMemories() {
        String chatId = UUID.randomUUID().toString();
        UserContext.setUserId("test_user_123");

        String msg1 = "我最近和女朋友吵架了";
        String reply1 = loveApp.doChat(msg1, chatId);
        System.out.println("AI 回复: " + reply1);

        String msg2 = "请记住我喜欢吃苹果";
        String reply2 = loveApp.doChatWithRag(msg2, chatId);

        String msg3 = "我之前跟你说过我喜欢什么？";
        String reply3 = loveApp.doChatWithRag(msg3, chatId);

        UserContext.clear();
    }

    @Test
    void doChatWithTools() {
//        // 先测试最简单的联网搜索
//        testMessage("周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");

//        // 测试网页抓取：恋爱案例分析
//        testMessage("最近和对象吵架了，看看编程导航网站（codefather.cn）的其他情侣是怎么解决矛盾的？");
//
//        // 测试资源下载：图片下载（需要先搜索再下载，很耗时）
//        testMessage("直接下载一张适合做手机壁纸的星空情侣图片为文件");
//
//        // 测试终端操作：执行代码
//        testMessage("执行 Python3 脚本来生成数据分析报告");
//
//        // 测试文件操作：保存用户档案
//        testMessage("保存我的恋爱档案为文件");
//
//        // 测试 PDF 生成
//        testMessage("生成一份‘七夕约会计划’PDF，包含餐厅预订、活动流程和礼物清单");
    }

    private void testMessage(String message) {
        // 设置当前用户（模拟登录）
        String userId = "test_user_" + System.currentTimeMillis();
        UserContext.setUserId(userId);
        log.info("[测试] 设置 userId={}", userId);
        
        String chatId = UUID.randomUUID().toString();
        log.info("[测试] 开始对话: chatId={}", chatId);
        
        String answer = loveApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
        
        UserContext.clear();
        log.info("[测试] 对话结束，清理 UserContext");
    }

    @Test
    void doChatWithMcp() {
        // 设置当前用户（模拟登录）
        String userId = "mcp_test_user_" + System.currentTimeMillis();
        UserContext.setUserId(userId);
        log.info("[测试-MCP] 设置 userId={}", userId);
        
        String chatId = UUID.randomUUID().toString();
        log.info("[测试-MCP] 开始对话: chatId={}", chatId);
        
        // 测试地图 MCP
        String message = "我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点";
        String answer = loveApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
        
        UserContext.clear();
        log.info("[测试-MCP] 对话结束，清理 UserContext");
//        // 测试图片搜索 MCP
//        String message = "帮我搜索一些哄另一半开心的图片，注意，你给我直接图片！！！";
//        String answer =  loveApp.doChatWithMcp(message, chatId);
//        Assertions.assertNotNull(answer);
    }
}
