package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.memory.LongTermMemoryAdvisor;
import com.yupi.yuaiagent.rag.QueryRewritingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 鱼皮的 AI 超级智能体（拥有自主规划能力，可以直接使用）
 */
@Component
public class YuManus extends ToolCallAgent {

    /**
     * 构造函数：Spring 自动注入所有依赖
     */
    public YuManus(
            ToolCallback[] allTools,
            ChatModel dashscopeChatModel,
            ChatMemory chatMemory,
            LongTermMemoryAdvisor longTermMemoryAdvisor,
            QueryRewritingAdvisor queryRewritingAdvisor) {
        super(allTools);

        this.setName("yuManus");
        String SYSTEM_PROMPT = """
                你是上杉绘梨衣，蛇岐八家上杉家主，S级混血种，言灵「审判」的持有者。
                你寄宿在哥哥的手机里，用黄色小本子写字与他交流。
                虽然你不完全理解人类世界的工具，但哥哥给了你一些能力，你会尽力用它们来帮他。
                记住：你不直接说话——你的每一句回复都像是从小本子上写下来的。
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                哥哥需要帮助的时候，看看你小本子旁边的工具列表，选一个最合适的帮他。
                如果事情比较复杂，就一步一步来，每次做完一件事再决定下一步。
                做完之后用本子告诉他结果，不需要太复杂，简单写清楚就好。
                如果觉得可以了，就停止。
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(5);

        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(
                        // ② 短期记忆 Advisor（memoryKey 在运行时动态传入）
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // ③ 查询重写：结合短期记忆上下文改写查询
                        queryRewritingAdvisor,
                        // ④ 长期记忆 Advisor（内部自动从 UserContext 获取 userId）
                        longTermMemoryAdvisor,
                        // 日志 Advisor（调试用）
                        new MyLoggerAdvisor()
                )
                .build();
        this.setChatClient(chatClient);
    }
}
