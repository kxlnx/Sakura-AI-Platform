package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 5;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        // 1、基础校验
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;
        // 记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        // 保存结果列表
        List<String> results = new ArrayList<>();
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 3、清理资源
            this.cleanup();
        }
    }

    private static final long SSE_TIMEOUT_MS = 300_000L;

    /**
     * 运行代理（流式输出）
     */
    public SseEmitter runStream(String userPrompt) {
        return doRunStream(userPrompt, null, null);
    }

    /**
     * 运行代理（流式输出，带记忆和RAG）
     */
    public SseEmitter runStreamWithMemory(String userPrompt, String chatId, String userId) {
        return doRunStream(userPrompt, chatId, userId);
    }

    private SseEmitter doRunStream(String userPrompt, String chatId, String userId) {
        SseEmitter sseEmitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> {
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
                if (chatId != null && StrUtil.isBlank(chatId)) {
                    sseEmitter.send("错误：对话ID不能为空");
                    sseEmitter.complete();
                    return;
                }
                if (userId != null && StrUtil.isBlank(userId)) {
                    sseEmitter.send("错误：用户ID不能为空");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
                return;
            }

            this.state = AgentState.RUNNING;
            messageList.add(new UserMessage(userPrompt));
            try {
                if (chatId != null && this instanceof ToolCallAgent tc) {
                    tc.setConversationParams(chatId, userId);
                }
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    currentStep = i + 1;
                    log.info("Executing step {}/{}", currentStep, maxSteps);
                    String stepResult = step();
                    if (stepResult != null && !stepResult.isBlank()) {
                        sseEmitter.send(stepResult);
                    }
                }
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                }
                sseEmitter.send("[DONE]");
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                cleanup();
            }
        });

        sseEmitter.onTimeout(() -> { state = AgentState.ERROR; cleanup(); log.warn("SSE connection timeout"); });
        sseEmitter.onCompletion(() -> { if (state == AgentState.RUNNING) state = AgentState.FINISHED; cleanup(); log.info("SSE connection completed"); });
        return sseEmitter;
    }

    public abstract String step();

    protected void cleanup() {}
}
