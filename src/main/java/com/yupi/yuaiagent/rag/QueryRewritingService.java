package com.yupi.yuaiagent.rag;

import com.yupi.yuaiagent.context.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询重写服务
 * 用于解决多轮对话中的指代性、省略性、上下文依赖性问题
 * 将用户当前问题结合历史上下文自动改写成完整的查询
 */
@Slf4j
@Service
public class QueryRewritingService {

    @Resource(name = "dashscopeChatModel")
    private ChatModel chatModel;

    @Resource
    private ChatMemory chatMemory;

    private static final String QUERY_REWRITING_PROMPT = "你是一个专业的对话理解助手，擅长处理多轮对话中的上下文依赖问题。\n\n" +
            "**任务**：结合最近的对话历史，将用户当前的问题改写为语义完整、独立可理解的查询语句。\n\n" +
            "**核心要求**：\n" +
            "1. **补全省略信息**：根据历史对话，补全用户问题中省略的所有核心对象、前提信息和背景。\n" +
            "2. **明确指代对象**：将用户问题中的代词（如‘那’、‘这个’、‘它’等）明确为具体的对象或概念。\n" +
            "3. **保持原始意图**：完全保留用户的原始意图和问题核心，不添加或修改语义。\n" +
            "4. **语言简洁自然**：改写后的语句应自然流畅，符合日常交流习惯，无冗余内容。\n\n" +
            "**最近的对话历史**：\n" +
            "{history}\n\n" +
            "**用户当前问题**：{current}\n\n" +
            "**输出要求**：\n" +
            "- 只输出改写后的完整查询语句\n" +
            "- 确保语句语义完整，可独立理解\n" +
            "- 不要添加任何解释或额外信息\n\n" +
            "改写结果：";

    /**
     * 重写查询
     * @param conversationId 会话ID（chatId）
     * @param current 当前问题
     * @return 改写后的完整查询
     */
    public String rewriteQuery(String conversationId, String current) {
        try {
            // 从 ChatMemory 获取历史对话
            List<Message> historyMessages = chatMemory.get(conversationId);
            // 只保留最近的2轮对话（4条消息），确保聚焦于最近的上下文
            String historyText = formatRecentHistory(historyMessages);
            
            String prompt = QUERY_REWRITING_PROMPT
                    .replace("{history}", historyText)
                    .replace("{current}", current);

            String rewrittenQuery = chatModel.call(prompt);
            log.info("[Query Rewriting] 原始问题: {}", current);
            log.info("[Query Rewriting] 改写后: {}", rewrittenQuery);
            return rewrittenQuery;
        } catch (Exception e) {
            log.error("[Query Rewriting] 重写失败", e);
            return current; // 失败时返回原始问题
        }
    }

    /**
     * 格式化最近的对话历史
     * 只保留最近的2轮对话，确保上下文聚焦且提示长度合理
     */
    public String formatRecentHistory(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "无历史对话"; 
        }
        
        // 只保留最近的4条消息（约2轮对话）
        int startIndex = Math.max(0, messages.size() - 4);
        List<Message> recentMessages = messages.subList(startIndex, messages.size());
        
        StringBuilder sb = new StringBuilder();
        for (Message message : recentMessages) {
            if (message instanceof UserMessage) {
                sb.append("用户：").append(message.getText()).append("\n");
            } else {
                sb.append("助手：").append(message.getText()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 检查是否需要重写
     * 基于多维度的智能判断
     */
    public boolean needRewriting(String current) {
        // 处理边界情况
        if (current == null || current.trim().isEmpty()) {
            return false;
        }
        
        // 纯标点符号检查
        String trimmed = current.trim();
        if (trimmed.equals("？") || trimmed.equals("?") || trimmed.equals("！") || trimmed.equals("!")) {
            return false;
        }
        
        // 1. 指示词检查（扩展版）
        String[] deicticWords = {
            "那", "这个", "那个", "它", "他们", "它们", "这些", "那些",
            "这里", "那里", "这儿", "那儿", "此时", "那时", "这样", "那样"
        };
        for (String word : deicticWords) {
            if (current.contains(word)) {
                return true;
            }
        }
        
        // 2. 省略句式检查
        String[] ellipsisIndicators = {
            "有吗", "是吗", "呢", "啊", "呀", "吧", "么", "嘛",
            "如何", "怎样", "怎么样", "如何做", "怎么做"
        };
        for (String indicator : ellipsisIndicators) {
            if (current.contains(indicator)) {
                return true;
            }
        }
        
        // 3. 简短问题检查（可能需要上下文）
        if (current.length() < 10 && (current.contains("?") || current.contains("？"))) {
            return true;
        }
        
        // 4. 依赖上下文的动词短语
        String[] contextDependentVerbs = {
            "继续", "接着", "然后", "后来", "之前", "以后", "之前说的", "刚才说的"
        };
        for (String verb : contextDependentVerbs) {
            if (current.contains(verb)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 获取ChatMemory实例（用于Advisor调用）
     */
    public ChatMemory getChatMemory() {
        return chatMemory;
    }
}