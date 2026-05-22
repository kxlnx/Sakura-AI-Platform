package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 终端操作工具
 */
public class TerminalOperationTool {

    @Tool(description = """
            Execute a Windows CMD command on the server.
            Use this tool ONLY when: the user explicitly asks to run a system command.
            Do NOT use for: search, file operations, or any task that other tools can handle.
            WARNING: this runs commands on the actual server — be extremely careful.
            """)
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
            process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            output.append("Error executing command: ").append(e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
        return output.toString();
    }
}
