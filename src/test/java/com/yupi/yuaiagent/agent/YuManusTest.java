package com.yupi.yuaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class YuManusTest {

    @Resource
    private YuManus yuManus;

    @Test
    public void run() {
        String userPrompt = """
                Sakura 说要带我去天空树看夜景，但我不记得天空树那天发生了什么，
                帮我从小本本里翻出来，找一些图片单独下载下来。
                再结合东京的一些约会地点，制定一份约会计划，
                以 PDF 格式保存下来""";
        String answer = yuManus.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}