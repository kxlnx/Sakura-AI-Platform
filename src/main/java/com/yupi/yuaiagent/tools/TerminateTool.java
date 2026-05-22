package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 终止工具（作用是让自主规划智能体能够合理地中断）
 */
public class TerminateTool {

    @Tool(description = """
            Terminate the current conversation turn.
            Use this tool when: you have completed all tasks and generated the final answer text — call this LAST.
            Do NOT call this with an empty response — always output your answer text BEFORE calling doTerminate.
            If you cannot complete the task, explain why in text first, then call doTerminate.
            """)
    public String doTerminate() {
        return "任务结束";
    }
}
