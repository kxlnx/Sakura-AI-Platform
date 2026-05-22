package com.yupi.yuaiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    public abstract boolean think();
    public abstract String act();

    @Override
    public String step() {
        try {
            boolean shouldAct = think();

            // 取 LLM 文字回复
            String text = "";
            List<Message> msgList = getMessageList();
            for (int i = msgList.size() - 1; i >= 0; i--) {
                if (msgList.get(i) instanceof AssistantMessage) {
                    String t = ((AssistantMessage) msgList.get(i)).getText();
                    if (t != null && !t.isBlank() && !t.equals("doTerminate")) {
                        text = t;
                        break;
                    }
                }
            }

            // 需要调工具 → 返回 LLM 思考文字给前端，工具结果仅作 LLM 上下文不对外展示
            if (shouldAct) {
                String result = text.isEmpty() ? "⚙️ 正在处理..." : text;
                act();
                return result;
            }

            // 没调工具 + 有文字 → 最终回复，终止
            if (!text.isBlank() && getState() != AgentState.FINISHED) {
                setState(AgentState.FINISHED);
            }
            return text;
        } catch (Exception e) {
            log.error("步骤执行失败", e);
            setState(AgentState.FINISHED);
            return "";
        }
    }

}
