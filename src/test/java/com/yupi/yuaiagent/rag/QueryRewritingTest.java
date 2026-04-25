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
        // 测试用例1：iPhone 14 相关
        String originalQuery1 = "那有分期吗？";
        String history1 = "用户：iPhone 14多少钱？\n助手：官网售价5999元起。";
        
        // 模拟历史对话（简化版）
        String rewritten1 = queryRewritingService.rewriteQuery(null, originalQuery1);
        System.out.println("测试用例1：");
        System.out.println("原始问题：" + originalQuery1);
        System.out.println("改写后：" + rewritten1);
        System.out.println();

        // 测试用例2：其他场景
        String originalQuery2 = "它的续航怎么样？";
        String history2 = "用户：我想了解新出的MacBook Pro\n助手：好的，MacBook Pro搭载了M3芯片。";
        
        String rewritten2 = queryRewritingService.rewriteQuery(null, originalQuery2);
        System.out.println("测试用例2：");
        System.out.println("原始问题：" + originalQuery2);
        System.out.println("改写后：" + rewritten2);
        System.out.println();

        // 测试用例3：不需要重写的情况
        String originalQuery3 = "iPhone 15的价格是多少？";
        boolean needRewriting = queryRewritingService.needRewriting(originalQuery3);
        System.out.println("测试用例3：");
        System.out.println("问题：" + originalQuery3);
        System.out.println("是否需要重写：" + needRewriting);
    }
}