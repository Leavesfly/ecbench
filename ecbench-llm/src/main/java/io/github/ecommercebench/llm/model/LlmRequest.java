package io.github.ecommercebench.llm.model;

import java.util.List;
import java.util.Map;

/** 一次模型生成请求。 */
public record LlmRequest(
    String model,
    List<ChatMessage> messages,
    List<ToolDefinition> tools,
    int maxTokens,
    String effort,
    String sessionId,
    Map<String, Object> extraBody) {
  public LlmRequest {
    messages = List.copyOf(messages);
    tools = List.copyOf(tools);
    extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
  }
}
