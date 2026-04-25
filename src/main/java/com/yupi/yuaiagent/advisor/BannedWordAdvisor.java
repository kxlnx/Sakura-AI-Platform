package com.yupi.yuaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * 自定义违禁词校验拦截器 (基于 Spring AI 1.0.0 最新 API)
 */
@Slf4j
public class BannedWordAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String BANNED_WORD = "分手";
    private static final String REJECT_MESSAGE = "对不起，我们不讨论破坏感情的话题哦。";

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // 优先级设为最高，确保在日志和记忆之前先拦截
        return 0;
    }

    private ChatClientRequest before(ChatClientRequest request) {
        log.info("AI Request: {}", request.prompt());
        return request;
    }

//    private void observeAfter(ChatClientResponse chatClientResponse) {
//        log.info("AI Response: {}", chatClientResponse.chatResponse().getResult().getOutput().getText());
//    }

    /**
     * 1. 拦截非流式请求 (doChat, doChatWithTools 等)
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        log.info("【非流式】进入违禁词校验...");

        // 1. 前置校验：提取用户的 Prompt
        // 注意：新版 API 中，用户的输入被封装在 request.prompt() 中
        String promptText = request.prompt().toString();
        if (promptText.contains(BANNED_WORD)) {
            log.warn("检测到违禁词，阻断请求！");
            // 在新版架构中，阻断请求最标准、最优雅的做法是抛出业务异常
            // 交由 Spring Boot 的 @ExceptionHandler 全局异常处理器去返回给前端
            // throw new IllegalArgumentException(REJECT_MESSAGE);
        }

        // 2. 放行：如果没有违禁词，交由责任链的下一个节点
        ChatClientResponse response = chain.nextCall(request);

        // 3. 后置校验（可选）：检查大模型返回的文本
        String aiReply = response.chatResponse().getResult().getOutput().getText();
        if (aiReply != null && aiReply.contains("渣男")) {
            log.warn("AI回复包含敏感词！");
            // 如果需要强行替换返回结果，你可以在这里抛出异常，或者重新组装 Response
            throw new IllegalArgumentException("AI 生成了不合规的内容，已被拦截。");
        }

        return response;
    }

    /**
     * 2. 拦截流式请求 (doChatByStream)
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        log.info("【流式】进入违禁词校验...");

        // 1. 前置校验
        String promptText = request.prompt().toString();
        if (promptText.contains(BANNED_WORD)) {
            log.warn("检测到违禁词，阻断流式请求！");
            // 流式拦截：返回一个包含错误信息的响应式流
            return Flux.error(new IllegalArgumentException(REJECT_MESSAGE));
        }

        // 2. 放行：获取流
        Flux<ChatClientResponse> responseFlux = chain.nextStream(request);

        // 3. 后置校验（难点）：
        // 就像你在 MyLoggerAdvisor 里写的一样，流式数据的后置处理必须使用 Aggregator 收集完碎片才能校验
        return new ChatClientMessageAggregator().aggregateChatClientResponse(responseFlux, chatClientResponse -> {
            String fullAiReply = chatClientResponse.chatResponse().getResult().getOutput().getText();
            if (fullAiReply != null && fullAiReply.contains("渣男")) {
                log.warn("【流式流出完毕】检测到 AI 输出了敏感词！");
                // 注意：这里流已经发给前端了，通常只能做后置日志审计或封号记录
            }
        });
    }
}