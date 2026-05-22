package com.yupi.yuaiagent.rag;

import com.yupi.yuaiagent.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 查询重写增强器
 * 用于解决多轮对话中的指代性、省略性、上下文依赖性问题
 * 在 RAG 检索前对查询进行重写，提高召回准确率
 * <p>
 * 执行顺序：order = 0，在短期记忆召回之后、长期记忆召回之前执行，
 * 结合短期记忆上下文重写查询，确保后续长期记忆和 RAG 使用完整表达
 */
@Slf4j
@Component
public class QueryRewritingAdvisor implements CallAdvisor, StreamAdvisor {

    private final QueryRewritingService queryRewritingService;

    public QueryRewritingAdvisor(QueryRewritingService queryRewritingService) {
        this.queryRewritingService = queryRewritingService;
    }

    @Override
    public String getName() {
        return "QueryRewritingAdvisor";
    }

    @Override
    public int getOrder() {
        return -200; // 必须在 RAG Advisor 之前执行，否则 RAG 的空上下文兜底会替换用户消息
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        try {
            String conversationId = (String) request.context().get(ChatMemory.CONVERSATION_ID);
            if (conversationId != null) {
                List<UserMessage> userMessages = request.prompt().getUserMessages();
                if (userMessages != null && !userMessages.isEmpty()) {
                    // 取最后一条 UserMessage（当前问题），第一条可能是历史消息
                    String currentMessage = userMessages.get(userMessages.size() - 1).getText();
                    if (currentMessage != null && !currentMessage.trim().isEmpty()) {
                        // 提取 userId：优先 request context，其次 UserContext
                        String userId = (String) request.context().get("userId");
                        if (userId == null) {
                            userId = UserContext.getUserId();
                        }
                        if (userId == null) {
                            userId = "default_user";
                        }

                        // 双条件判断：有记忆 + 关键词模糊 → 重写
                        if (queryRewritingService.needRewriting(conversationId, userId, currentMessage)) {
                            String rewrittenQuery = queryRewritingService.rewriteQuery(conversationId, currentMessage);
                            log.info("[QueryRewritingAdvisor] 重写查询: {} -> {}", currentMessage, rewrittenQuery);

                            Prompt newPrompt = request.prompt().augmentUserMessage(rewrittenQuery);
                            request = new ChatClientRequest(newPrompt, request.context());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[QueryRewritingAdvisor] 处理失败", e);
        }

        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request);
    }

}