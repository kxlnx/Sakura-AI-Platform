package com.yupi.yuaiagent.rag;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

/**
 * 创建上下文查询增强器的工厂
 */
public class LoveAppContextualQueryAugmenterFactory {

    /**
     * 创建恋爱应用专用的上下文查询增强器实例
     * 当用户提问与恋爱无关时，返回预设的提示信息
     *
     * @return ContextualQueryAugmenter 配置好的上下文查询增强器实例
     */
    public static ContextualQueryAugmenter createInstance() {
        PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
                你应该输出下面的内容：
                抱歉，我只能回答恋爱相关的问题，别的没办法帮到您哦，
                有问题可以联系编程导航客服 https://codefather.cn
                """);
        // 简单一句话：这个工厂类是在给 AI 装一个“安检门”，只有当 AI 发现自己“兜里没货”（搜不到本地知识）时，安检门才会强制它报出那段预设的拒绝台词。
        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(false)
                .emptyContextPromptTemplate(emptyContextPromptTemplate)
                .build();
    }
}
