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
 * 违禁词拦截器 —— 非流式和流式路径行为一致
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
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String promptText = request.prompt().toString();
        if (promptText.contains(BANNED_WORD)) {
            log.warn("检测到违禁词，阻断非流式请求！");
            throw new IllegalArgumentException(REJECT_MESSAGE);
        }

        ChatClientResponse response = chain.nextCall(request);

        String aiReply = response.chatResponse().getResult().getOutput().getText();
        if (aiReply != null && aiReply.contains("渣男")) {
            log.warn("AI回复包含敏感词，拦截！");
            throw new IllegalArgumentException("AI 生成了不合规的内容，已被拦截。");
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String promptText = request.prompt().toString();
        if (promptText.contains(BANNED_WORD)) {
            log.warn("检测到违禁词，阻断流式请求！");
            return Flux.error(new IllegalArgumentException(REJECT_MESSAGE));
        }

        Flux<ChatClientResponse> responseFlux = chain.nextStream(request);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(responseFlux, chatClientResponse -> {
            String fullAiReply = chatClientResponse.chatResponse().getResult().getOutput().getText();
            if (fullAiReply != null && fullAiReply.contains("渣男")) {
                log.warn("流式输出完毕，检测到 AI 输出了敏感词！");
            }
        });
    }
}
