package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 查询重写增强器
 * 用于解决多轮对话中的指代性、省略性、上下文依赖性问题
 * 在 RAG 检索前对查询进行重写，提高召回准确率
 */
@Slf4j
public class QueryRewritingAdvisor implements CallAdvisor, StreamAdvisor {

    private final QueryRewritingService queryRewritingService;

    private final VectorStore vectorStore;

    public QueryRewritingAdvisor(QueryRewritingService queryRewritingService, VectorStore vectorStore) {
        this.queryRewritingService = queryRewritingService;
        this.vectorStore = vectorStore;
    }

    @Override
    public String getName() {
        return "QueryRewritingAdvisor";
    }

    @Override
    public int getOrder() {
        return -100; // 优先级高于 QuestionAnswerAdvisor
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        try {
            String conversationId = (String) request.context().get(ChatMemory.CONVERSATION_ID);
            if (conversationId != null) {
                // 获取用户消息
                List<UserMessage> userMessages = request.prompt().getUserMessages();
                if (userMessages != null && !userMessages.isEmpty()) {
                    String currentMessage = userMessages.get(0).getText();
                    if (currentMessage != null && !currentMessage.trim().isEmpty()) {
                        // 智能判断是否需要重写
                        if (queryRewritingService.needRewriting(currentMessage)) {
                            // 重写查询，传递完整的conversationId
                            String rewrittenQuery = queryRewritingService.rewriteQuery(conversationId, currentMessage);
                            log.info("[QueryRewritingAdvisor] 重写查询: {} -> {}", currentMessage, rewrittenQuery);
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

    /**
     * 创建带查询重写的 QuestionAnswerAdvisor
     */
    public static QuestionAnswerAdvisor createWithRewriting(VectorStore vectorStore) {
        return new QuestionAnswerAdvisor(vectorStore);
    }
}