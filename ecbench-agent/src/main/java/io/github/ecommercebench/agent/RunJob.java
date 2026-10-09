package io.github.ecommercebench.agent;

import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ToolDefinition;

import java.util.List;

/**
 * 一次 episode 的输入规格；字段对齐 Python `_build_default_job` 返回的 job dict。
 *
 * <p>{@code initialMessages} 通常为 [system, user] 两条；{@code toolSchemas} 为发往模型的工具定义列表。
 * 其余上限字段驱动主循环与上下文裁剪。
 */
public record RunJob(
        String task,
        int idx,
        String dataSource,
        List<ChatMessage> initialMessages,
        List<ToolDefinition> toolSchemas,
        int maxTurns,
        int maxDays,
        int maxTokenCapacity,
        int maxToolResponseChars) {

    public RunJob {
        initialMessages = List.copyOf(initialMessages);
        toolSchemas = List.copyOf(toolSchemas);
    }
}
