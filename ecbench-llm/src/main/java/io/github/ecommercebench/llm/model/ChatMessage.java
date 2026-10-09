package io.github.ecommercebench.llm.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 兼容多个 Provider 的统一消息模型；cleared 消息仍保留在审计历史中。
 *
 * @param role 消息角色（system/user/assistant/tool）
 * @param toolCalls 本消息（多为 assistant）发起的工具调用列表
 * @param toolCallId 当 role=TOOL 时回填的对应工具调用 id
 * @param reasoningItems Provider 返回的原始推理块（如 Anthropic thinking），供下一轮回放
 * @param cleared 上下文清理后标记为已清空：保留在历史但不发送给 Provider
 */
public record ChatMessage(
        ChatRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String reasoningContent,
        List<ReasoningItem> reasoningItems,
        boolean cleared,
        Map<String, Object> metadata) {

    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        reasoningItems = reasoningItems == null ? List.of() : List.copyOf(reasoningItems);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ChatMessage system(String content) {
        return simple(ChatRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return simple(ChatRole.USER, content);
    }

    public static ChatMessage assistant(
            String content,
            List<ToolCall> toolCalls,
            String reasoningContent,
            List<ReasoningItem> reasoningItems) {
        return new ChatMessage(
                ChatRole.ASSISTANT,
                content,
                toolCalls,
                null,
                reasoningContent,
                reasoningItems,
                false,
                Map.of());
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(
                ChatRole.TOOL, content, List.of(), toolCallId, null, List.of(), false, Map.of());
    }

    private static ChatMessage simple(ChatRole role, String content) {
        return new ChatMessage(role, content, List.of(), null, null, List.of(), false, Map.of());
    }

    /** 返回一份标记为已清空的新消息：仍留在审计历史但不再参与 Provider 请求。 */
    public ChatMessage markCleared() {
        return new ChatMessage(
                role, content, toolCalls, toolCallId, reasoningContent, reasoningItems, true, metadata);
    }

    /** 生成发往 Provider 的视图：已清空消息返回 Optional.empty() 以从请求中过滤掉。 */
    public Optional<ChatMessage> forProvider() {
        return cleared ? Optional.empty() : Optional.of(this);
    }
}
