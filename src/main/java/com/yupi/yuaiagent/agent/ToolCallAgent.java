package com.yupi.yuaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    @Value("${sakura.tool.result-truncate-length:600}")
    private int toolResultTruncateLength;

    private final ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
    private final ChatOptions chatOptions;

    // 对话相关参数
    private String chatId;
    private String userId;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }

    /**
     * 设置对话参数
     * @param chatId 对话ID
     * @param userId 用户ID
     */
    public void setConversationParams(String chatId, String userId) {
        this.chatId = chatId;
        this.userId = userId;
    }

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动
     */
    @Override
    public boolean think() {
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        try {
            // 构建聊天客户端调用，添加记忆和RAG支持
            var chatClientCall = getChatClient().prompt(prompt)
                    .system(getSystemPrompt());
            // nextStepPrompt 通过 system 消息注入，不写入 messageList，避免污染数据库
            if (StrUtil.isNotBlank(getNextStepPrompt())) {
                chatClientCall = chatClientCall.system(getNextStepPrompt());
            }
            chatClientCall = chatClientCall.toolCallbacks(availableTools);  // ✅ 修复：使用 toolCallbacks() 而非 tools()

            if (StrUtil.isNotBlank(chatId) && StrUtil.isNotBlank(userId)) {
                String memoryKey = userId + ":" + chatId;
                chatClientCall = chatClientCall.advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, memoryKey);
                    spec.param("userId", userId);
                });
            }

            ChatResponse chatResponse = chatClientCall
                    .call()
                    .chatResponse();

            this.toolCallChatResponse = chatResponse;

            /*
             * 解析AI返回的工具调用信息并记录日志
             * 提取助手消息、工具调用列表及其参数
             */
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            String result = assistantMessage.getText();
            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);

            /*
             * 根据是否需要调用工具返回不同结果
             * 无工具调用时手动记录助手消息，有工具调用时由框架自动记录
             */
            if (toolCallList.isEmpty()) {
                getMessageList().add(assistantMessage);
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            /*
             * 异常处理：记录错误信息并返回false
             */
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            return false;
        }
    }

    /**
     * 执行工具调用并处理结果
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        // 前置校验：拦截明显编造的参数
        AssistantMessage assistantMessage = toolCallChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        for (AssistantMessage.ToolCall call : toolCalls) {
            String validationError = validateToolCall(call);
            if (validationError != null) {
                log.warn("[ToolCallAgent] 参数校验不通过: {} → {}", call.name(), validationError);
                getMessageList().add(new AssistantMessage(
                    "工具调用被拦截: " + validationError + "。请向用户说明情况，询问正确的信息。"));
                return "工具调用被拦截: " + validationError;
            }
        }

        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            // 任务结束，更改状态
            setState(AgentState.FINISHED);
        }
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> {
                    String data = response.responseData();
                    // 截断过长结果，避免前端被海量 HTML 淹没
                    String displayData = data != null && data.length() > toolResultTruncateLength
                            ? data.substring(0, toolResultTruncateLength) + "...（已截断，完整数据已用于后续分析）"
                            : data;
                    return "【" + response.name() + "】返回：" + displayData;
                })
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;
    }

    /**
     * 参数校验：拦截 LLM 编造的参数
     * @return 错误信息，null 表示通过
     */
    private String validateToolCall(AssistantMessage.ToolCall call) {
        String args = call.arguments();
        if (args == null || args.isBlank()) {
            return "参数为空，工具调用被拒绝";
        }
        // 检查是否包含明显的虚构内容（纯随机字符串）
        if (args.matches(".*\"[a-zA-Z0-9]{30,}\".*")) {
            return "参数疑似随机字符串，请提供真实信息";
        }
        // URL 参数检查
        if (args.toLowerCase().contains("url") && !args.contains("http")) {
            return "URL 参数格式不正确，需要 http/https 前缀";
        }
        // 空参数检查
        if (args.contains("\"\"") || args.contains("''")) {
            return "存在空参数，请补全必要信息";
        }
        return null;
    }
}
