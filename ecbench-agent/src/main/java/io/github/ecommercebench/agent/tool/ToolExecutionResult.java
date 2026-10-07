package io.github.ecommercebench.agent.tool;

/**
 * 一次工具调用的执行结果。
 *
 * <p>{@code content} 是回传给模型的 JSON 文本，将作为 {@code role="tool"} 消息内容，并用 {@code toolCallId} 与发起的调用关联。
 */
public record ToolExecutionResult(String toolCallId, String toolName, String content) {}
