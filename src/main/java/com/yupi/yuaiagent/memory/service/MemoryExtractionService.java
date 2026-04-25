package com.yupi.yuaiagent.memory.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 记忆提取服务
 * 使用大模型智能分析用户消息，提取关键事实
 */
@Slf4j
@Service
public class MemoryExtractionService {

    @Resource(name = "dashscopeChatModel")
    private ChatModel chatModel;

    /**
     * 从显式指令中提取记忆
     * 用户说："请记住我喜欢吃苹果"
     * 输出：["我喜欢吃苹果"]
     */
    public List<String> extractFromExplicitInstruction(String message) {
        try {
            String promptTemplate = """
                    你是一个记忆提取助手。用户要求你记住某些信息。

                    用户输入：{input}

                    请分析用户输入，提取出用户想要记住的具体事实。
                    规则：
                    1. 只提取用户明确要求记住的内容
                    2. 提取时要简洁，去除无关词语
                    3. 如果用户说"请记住XXX"，只提取XXX部分
                    4. 如果有多个事实，用换行分隔

                    输出格式：
                    - 每行一个事实
                    - 不要加任何前缀或解释
                    - 只输出事实本身

                    示例：
                    输入：請記住我喜歡吃蘋果
                    輸出：我喜歡吃蘋果

                    输入：记住我叫Ken，我是程序员
                    輸出：我叫Ken
                    輸出：我是程序员
                    """;

            PromptTemplate template = new PromptTemplate(promptTemplate);
            Prompt prompt = template.create(Map.of("input", message));

            ChatResponse response = chatModel.call(prompt);

            return parseExtractedFacts(response.getResult().getOutput().getText());

        } catch (Exception e) {
            log.error("[LLM记忆提取] 显式提取失败: {}", e.getMessage());
            return fallbackExtractExplicit(message);
        }
    }

    /**
     * 从对话内容中隐式提取关键事实
     * 用户说："我是一名程序员，在北京工作"
     * 输出：["职业是程序员", "工作地点是北京"]
     */
    public List<String> extractFromConversation(String message) {
        try {
            String promptTemplate = """
                    你是一个记忆提取助手。你的任务是从用户对话中识别并提取关键事实。

                    用户输入：{input}

                    请分析用户输入，识别其中的关键信息，如：
                    - 基本信息：姓名、年龄、生日、性别等
                    - 职业信息：工作、职业、职位、公司等
                    - 位置信息：居住地、工作地点、城市等
                    - 偏好信息：喜欢什么、讨厌什么、爱好等
                    - 关系状态：单身、恋爱中、已婚等
                    - 重要事件：最近发生的事、计划、目标等

                    规则：
                    1. 只提取客观、可记忆的事实
                    2. 用简洁的语言描述（如"职业是程序员"而不是"我是一名程序员"）
                    3. 忽略情感表达和主观描述
                    4. 如果没有有价值的信息，输出"无"

                    输出格式：
                    - 每行一个事实
                    - 不要加任何前缀或解释
                    - 只输出事实本身

                    示例：
                    输入：我叫小明，今年28岁，在腾讯工作
                    輸出：名字是小明
                    輸出：年龄28岁
                    輸出：在腾讯工作

                    输入：我最近和女朋友吵架了，心情很不好
                    輸出：恋爱中
                    輸出：最近和女朋友吵架

                    输入：今天天气真好啊
                    輸出：无
                    """;

            PromptTemplate template = new PromptTemplate(promptTemplate);
            Prompt prompt = template.create(Map.of("input", message));

            ChatResponse response = chatModel.call(prompt);

            List<String> results = parseExtractedFacts(response.getResult().getOutput().getText());

            // 如果没有提取到有价值的信息，返回空列表
            if (results.size() == 1 && "无".equals(results.get(0))) {
                return new ArrayList<>();
            }

            return results;

        } catch (Exception e) {
            log.error("[LLM记忆提取] 隐式提取失败: {}", e.getMessage());
            return fallbackExtractImplicit(message);
        }
    }

    /**
     * 解析 LLM 输出，提取事实列表
     */
    private List<String> parseExtractedFacts(String output) {
        List<String> facts = new ArrayList<>();

        if (output == null || output.isEmpty()) {
            return facts;
        }

        // 按行分割
        String[] lines = output.split("\n");

        for (String line : lines) {
            // 清理每行
            line = line.trim();

            // 跳过空行
            if (line.isEmpty()) {
                continue;
            }

            // 移除常见的列表前缀
            line = line.replaceAll("^[①②③④\\d+、.。\\-\\*\\s]+", "");
            line = line.trim();

            // 移除引号
            line = line.replaceAll("^[\"''『』「」【】]+", "");
            line = line.replaceAll("[\"''『』「」【】]+$", "");

            line = line.trim();

            // 跳过"无"和太短的行
            if (line.isEmpty() || line.length() < 2) {
                continue;
            }

            // 跳过明确的无意义内容
            if (line.contains("无") || line.contains("没有") || line.contains("不记得")) {
                continue;
            }

            facts.add(line);
        }

        return facts;
    }

    /**
     * 降级方案：简单的规则提取（显式）
     */
    private List<String> fallbackExtractExplicit(String message) {
        List<String> facts = new ArrayList<>();

        // 尝试提取"记住"后面的内容
        if (message.contains("记住")) {
            int index = message.indexOf("记住");
            String fact = message.substring(index + 2).trim();
            // 清理常见前缀
            fact = fact.replaceAll("^[：:，,]+\\s*", "");
            if (!fact.isEmpty()) {
                facts.add(fact);
            }
        }

        return facts;
    }

    /**
     * 降级方案：简单的规则提取（隐式）
     */
    private List<String> fallbackExtractImplicit(String message) {
        List<String> facts = new ArrayList<>();

        // 简单模式匹配
        Pattern[] patterns = {
            Pattern.compile("我叫([^，,。\\s]+)"),
            Pattern.compile("我的名字是([^，,。\\s]+)"),
            Pattern.compile("我是([^，,。]+)"),
            Pattern.compile("在([^，,。\\s]+)工作"),
            Pattern.compile("住在([^，,。\\s]+)"),
            Pattern.compile("喜欢([^，,。\\s]+)"),
            Pattern.compile("从事([^，,。\\s]+)工作")
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                facts.add(matcher.group(0));
            }
        }

        return facts;
    }
}
