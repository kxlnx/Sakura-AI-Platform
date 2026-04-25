package com.yupi.yuaiagent.memory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 混合搜索测试
 * 验证稠密向量搜索 + BM25 融合是否生效
 */
@SpringBootTest
public class HybridSearchTest {

    @Autowired
    private LongTermMemoryWriter longTermMemoryWriter;

    @Autowired
    private LongTermMemoryReader longTermMemoryReader;

    private static final String TEST_USER_ID = "hybrid_search_test_user";

    @Test
    void testHybridSearchBasic() {
        System.out.println("=== 混合搜索基础测试 ===\n");

        // 1. 准备测试数据
        System.out.println("1. 写入测试记忆...");
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我喜欢吃苹果", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我是程序员，在科技公司工作", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我喜欢蓝色", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我的生日是10月15日", null);
        longTermMemoryWriter.writeMemory(TEST_USER_ID, "我喜欢运动，特别是打篮球", null);

        // 2. 测试不同查询
        testQuery("水果相关", "我喜欢吃什么水果？");
        testQuery("职业相关", "我的职业是什么？");
        testQuery("颜色相关", "我喜欢什么颜色？");
        testQuery("生日相关", "我的生日是哪天？");
        testQuery("运动相关", "我喜欢什么运动？");
    }

    private void testQuery(String label, String query) {
        System.out.println("\n--- " + label + " ---");
        System.out.println("查询内容: \"" + query + "\"");

        List<Document> results = longTermMemoryReader.readMemory(TEST_USER_ID, query, 3);

        System.out.println("召回结果数: " + results.size());
        for (int i = 0; i < results.size(); i++) {
            System.out.println("[" + (i + 1) + "] " + results.get(i).getText());
        }

        Assertions.assertFalse(results.isEmpty(), "应该至少召回1条记忆");
    }

    @Test
    void testPrecision() {
        System.out.println("\n=== 测试召回精度 ===\n");

        String query = "我喜欢吃什么水果？";
        List<Document> results = longTermMemoryReader.readMemory(TEST_USER_ID, query, 3);

        System.out.println("查询: " + query);
        System.out.println("结果:");
        for (int i = 0; i < results.size(); i++) {
            System.out.println("[" + (i + 1) + "] " + results.get(i).getText());
        }

        // 第一条应该是最相关的"我喜欢吃苹果"
        if (!results.isEmpty()) {
            Document topResult = results.get(0);
            Assertions.assertTrue(topResult.getText().contains("苹果"),
                    "第一条结果应该包含'苹果'");
        }
    }
}