package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ImageSearchToolTest {

    @Value("${pexels.api-key:}")
    private String pexelsApiKey;

    @Test
    void searchImage() {
        if (pexelsApiKey == null || pexelsApiKey.isBlank()
                || pexelsApiKey.contains("改为")) {
            System.out.println("[跳过] 未配置 Pexels API Key，跳过图片搜索测试");
            return;
        }
        ImageSearchTool tool = new ImageSearchTool(pexelsApiKey);
        String result = tool.searchImage("cat");
        System.out.println("搜索结果: " + result);
        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.contains("Error"));
    }
}
