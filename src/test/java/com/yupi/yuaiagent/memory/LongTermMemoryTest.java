package com.yupi.yuaiagent.memory;

import com.yupi.yuaiagent.app.LoveApp;
import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@SpringBootTest
class LongTermMemoryTest {

    @Resource
    private LoveApp loveApp;

    @Resource
    private LongTermMemoryWriter longTermMemoryWriter;

    @Resource
    private LongTermMemoryReader longTermMemoryReader;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String TEST_USER_ID = "test_user_123";
    private static final String TEST_CHAT_ID = "test_chat_long_term";

    @Test
    void testShortTermMemory() {
        String userId = TEST_USER_ID;
        String chatId = "test_short_term_" + System.currentTimeMillis();
        UserContext.setUserId(userId);

        String msg1 = "我最近和女朋友吵架了，很不开心";
        String reply1 = loveApp.doChat(msg1, chatId);
        Assertions.assertNotNull(reply1, "AI 应该返回回复");

        String msg2 = "她说我不关心她";
        String reply2 = loveApp.doChat(msg2, chatId);
        Assertions.assertNotNull(reply2, "AI 应该返回回复");

        String redisKey = "chat:memory:" + userId + ":" + chatId;
        Long size = stringRedisTemplate.opsForList().size(redisKey);
        Assertions.assertNotNull(size, "Redis 中应该有短期记忆");
        Assertions.assertTrue(size > 0, "Redis 中应该有至少1条记忆");

        stringRedisTemplate.delete(redisKey);
        UserContext.clear();
    }

    /**
     * 测试2：长期记忆写入和读取
     * 验证 Milvus 向量库存储和检索
     */
    @Test
    void testLongTermMemoryWriteAndRead() {
        // 写入一条长期记忆
        String fact = "我喜欢吃苹果";
        longTermMemoryWriter.writeMemory(TEST_USER_ID, fact, null);

        // 读取记忆
        List<Document> memories = longTermMemoryReader.readMemory(TEST_USER_ID, "我喜欢什么水果", 5);
        Assertions.assertFalse(memories.isEmpty(), "应该召回至少一条记忆");

        System.out.println("召回的记忆:");
        for (Document doc : memories) {
            System.out.println("  - " + doc.getText());
        }
    }

    /**
     * 测试3：显式记忆指令处理
     * 验证 "请记住" 指令的提取和存储
     */
    @Test
    void testExplicitMemory() {
        // 发送显式记忆指令
        String message = "请记住我喜欢蓝色";
        longTermMemoryWriter.handleExplicitMemory(TEST_USER_ID, message);

        // 查询验证
        List<Document> memories = longTermMemoryReader.readMemory(TEST_USER_ID, "我喜欢什么颜色", 5);
        Assertions.assertFalse(memories.isEmpty(), "应该召回显式记忆");

        System.out.println("显式记忆召回成功:");
        for (Document doc : memories) {
            System.out.println("  - " + doc.getText());
        }
    }

    /**
     * 测试4：隐式记忆提取
     * 验证包含个人信息的对话会被自动存储
     */
    @Test
    void testImplicitMemory() {
        // 发送包含个人信息的对话
        String message = "我是一名程序员，在一家科技公司工作";
        longTermMemoryWriter.handleImplicitMemory(TEST_USER_ID, message);

        // 查询验证
        List<Document> memories = longTermMemoryReader.readMemory(TEST_USER_ID, "我的工作是什么", 5);
        Assertions.assertFalse(memories.isEmpty(), "应该召回隐式记忆");

        System.out.println("隐式记忆召回成功:");
        for (Document doc : memories) {
            System.out.println("  - " + doc.getText());
        }
    }

    /**
     * 测试5：触发关键词检测
     * 验证 shouldTrigger 方法能正确识别需要触发长期记忆的场景
     */
    @Test
    void testTriggerKeywords() {
        // 应该触发的情况
        String[] shouldTrigger = {
                "你记得我喜欢什么吗？",
                "我之前跟你说过",
                "我的偏好是什么",
                "之前提到的",
                "关于我喜欢的事情"
        };

        // 不应该触发的情况
        String[] shouldNotTrigger = {
                "今天天气怎么样",
                "给我讲个笑话",
                "如何学习编程"
        };

        System.out.println("测试应该触发的情况:");
        for (String msg : shouldTrigger) {
            boolean result = longTermMemoryReader.shouldTrigger(msg);
            System.out.println("  [" + msg + "] -> " + (result ? "触发" : "不触发"));
            Assertions.assertTrue(result, "应该触发: " + msg);
        }

        System.out.println("测试不应该触发的情况:");
        for (String msg : shouldNotTrigger) {
            boolean result = longTermMemoryReader.shouldTrigger(msg);
            System.out.println("  [" + msg + "] -> " + (result ? "触发" : "不触发"));
            Assertions.assertFalse(result, "不应该触发: " + msg);
        }
    }

    /**
     * 测试6：多路召回测试
     * 验证向量搜索 + 关键词增强的融合效果
     */
    @Test
    void testMultiWayRecall() {
        // 先写入多条记忆
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我喜欢吃苹果", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我喜欢蓝色", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我在科技公司工作", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我是一个程序员", null);

        // 查询
        List<Document> memories = longTermMemoryReader.readMemory(TEST_USER_ID, "我喜欢什么水果", 5);

        System.out.println("多路召回结果:");
        System.out.println("  查询: 我喜欢什么水果");
        System.out.println("  召回数量: " + memories.size());
        for (Document doc : memories) {
            System.out.println("  - " + doc.getText());
        }

        Assertions.assertFalse(memories.isEmpty(), "应该召回记忆");
    }

    /**
     * 测试7：记忆去重测试
     * 验证相同的记忆不会重复写入
     */
    @Test
    void testMemoryDeduplication() {
        String fact = "我喜欢吃香蕉";

        // 第一次写入
        longTermMemoryWriter.writeMemory(TEST_USER_ID, fact, null);

        // 第二次写入（应该被去重）
        longTermMemoryWriter.writeMemory(TEST_USER_ID, fact, null);

        // 验证只写入了一条
        List<Document> memories = longTermMemoryReader.readMemory(TEST_USER_ID, "我喜欢吃香蕉", 10);

        System.out.println("去重测试:");
        System.out.println("  写入相同内容2次后，召回数量: " + memories.size());

        // 统计包含"我喜欢吃香蕉"的数量
        long count = memories.stream()
                .filter(doc -> doc.getText().contains("我喜欢吃香蕉"))
                .count();
        Assertions.assertTrue(count <= 1, "相同记忆应该只保留一条");
    }

    @Test
    void testBothMemoriesIntegration() {
        String userId = TEST_USER_ID;
        String chatId = "test_integration_" + System.currentTimeMillis();
        UserContext.setUserId(userId);

        String msg1 = "我最近和女朋友吵架了";
        String reply1 = loveApp.doChat(msg1, chatId);

        String msg2 = "请记住我喜欢吃苹果，还有我是程序员";
        String reply2 = loveApp.doChatWithRag(msg2, chatId);

        String msg3 = "我之前跟你说过我喜欢什么？还有我是做什么工作的？";
        String reply3 = loveApp.doChatWithRag(msg3, chatId);

        String msg4 = "那你能给我一些恋爱建议吗？";
        String reply4 = loveApp.doChatWithRag(msg4, chatId);

        String redisKey = "chat:memory:" + userId + ":" + chatId;
        Long size = stringRedisTemplate.opsForList().size(redisKey);

        stringRedisTemplate.delete(redisKey);
        UserContext.clear();
    }

    /**
     * 测试9：记忆转换为系统消息
     * 验证 convertToSystemMessage 方法
     */
    @Test
    void testConvertToSystemMessage() {
        // 写入几条记忆
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我喜欢苹果", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我是程序员", null);

        // 读取并转换
        List<Document> memories = longTermMemoryReader.readMemory(TEST_USER_ID, "我喜欢什么", 5);
        String systemMessage = longTermMemoryReader.convertToSystemMessage(memories);

        System.out.println("转换后的系统消息:");
        System.out.println(systemMessage);

        Assertions.assertFalse(systemMessage.isEmpty(), "系统消息不应该为空");
        Assertions.assertTrue(systemMessage.contains("【用户长期记忆】"), "应该包含标题");
    }

    /**
     * 测试10：跨会话的长期记忆
     * 验证用户ID相同的情况下，长期记忆可以跨会话访问
     */
    @Test
    void testCrossSessionMemory() {
        String userId = "cross_session_user";

        // 会话1：写入记忆
        System.out.println("会话1：写入记忆");
        longTermMemoryWriter.writeMemory(userId, "我在北京工作", null);

        // 会话2：读取记忆（使用不同的chatId但相同的userId）
        System.out.println("会话2：读取记忆（跨会话）");
        List<Document> memories = longTermMemoryReader.readMemory(userId, "我在哪里工作", 5);

        System.out.println("跨会话召回结果:");
        for (Document doc : memories) {
            System.out.println("  - " + doc.getText());
        }

        Assertions.assertFalse(memories.isEmpty(), "跨会话应该能召回记忆");
    }
}
