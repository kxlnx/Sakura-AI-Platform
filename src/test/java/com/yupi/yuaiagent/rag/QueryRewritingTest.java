package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 查询重写功能测试
 * 验证多轮对话中的上下文聚合和查询重写
 */
@SpringBootTest
class QueryRewritingTest {

    @Resource
    private QueryRewritingService queryRewritingService;

    @Test
    void testQueryRewriting() {
        String convId = "test_user:test_chat";
        String userId = "test_user";

        // 测试用例1：有指代词，且有短期记忆 → 应该重写
        String originalQuery1 = "那有分期吗？";
        String rewritten1 = queryRewritingService.rewriteQuery(convId, originalQuery1);
        System.out.println("测试用例1：");
        System.out.println("原始问题：" + originalQuery1);
        System.out.println("改写后：" + rewritten1);
        System.out.println();

        // 测试用例2：完整问题，无指代 → 不需要重写
        String originalQuery2 = "iPhone 15的价格是多少？";
        boolean needRewriting2 = queryRewritingService.needRewriting(convId, userId, originalQuery2);
        System.out.println("测试用例2：");
        System.out.println("问题：" + originalQuery2);
        System.out.println("是否需要重写：" + needRewriting2);

        // 测试用例3：有指代词
        String originalQuery3 = "它续航怎么样？";
        boolean needRewriting3 = queryRewritingService.needRewriting(convId, userId, originalQuery3);
        System.out.println("测试用例3：");
        System.out.println("问题：" + originalQuery3);
        System.out.println("是否需要重写：" + needRewriting3);
    }
}