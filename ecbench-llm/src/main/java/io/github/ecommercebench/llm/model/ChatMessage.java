package io.github.ecommercebench.llm.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 兼容多个 Provider 的统一消息模型；cleared 消息仍保留在审计历史中。 */
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

  public ChatMessage markCleared() {
    return new ChatMessage(
        role, content, toolCalls, toolCallId, reasoningContent, reasoningItems, true, metadata);
  }

  public Optional<ChatMessage> forProvider() {
    return cleared ? Optional.empty() : Optional.of(this);
  }
}
