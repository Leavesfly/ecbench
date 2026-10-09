package io.github.ecommercebench.llm.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Provider 响应的统一表示。
 */
public record LlmResponse(
        String content,
        List<ToolCall> toolCalls,
        String reasoningContent,
        List<ReasoningItem> reasoningItems,
        JsonNode rawResponse) {
    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        reasoningItems = reasoningItems == null ? List.of() : List.copyOf(reasoningItems);
    }

    public ChatMessage toAssistantMessage() {
        return ChatMessage.assistant(content, toolCalls, reasoningContent, reasoningItems);
    }
}
