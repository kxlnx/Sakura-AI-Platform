package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

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
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        UserContext.setUserId("12123");
        String message = "我叫什么？";
        String answer = loveApp.doChatWithRag(message, chatId);
        UserContext.clear();
        Assertions.assertNotNull(answer);
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
        // 测试联网搜索问题的答案
        testMessage("周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");

        // 测试网页抓取：恋爱案例分析
        testMessage("最近和对象吵架了，看看编程导航网站（codefather.cn）的其他情侣是怎么解决矛盾的？");

        // 测试资源下载：图片下载
        testMessage("直接下载一张适合做手机壁纸的星空情侣图片为文件");

        // 测试终端操作：执行代码
        testMessage("执行 Python3 脚本来生成数据分析报告");

        // 测试文件操作：保存用户档案
        testMessage("保存我的恋爱档案为文件");

        // 测试 PDF 生成
        testMessage("生成一份‘七夕约会计划’PDF，包含餐厅预订、活动流程和礼物清单");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = loveApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();
         // 测试地图 MCP
        String message = "我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点";
        String answer =  loveApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
//        // 测试图片搜索 MCP
//        String message = "帮我搜索一些哄另一半开心的图片，注意，你给我直接图片！！！";
//        String answer =  loveApp.doChatWithMcp(message, chatId);
//        Assertions.assertNotNull(answer);
    }
}
