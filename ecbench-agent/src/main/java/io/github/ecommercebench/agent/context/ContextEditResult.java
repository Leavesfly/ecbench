package io.github.ecommercebench.agent.context;

import io.github.ecommercebench.llm.model.ChatMessage;
import java.util.List;

/** 一次上下文编辑的结果和可注入模型的状态提示。 */
public record ContextEditResult(
    List<ChatMessage> messages, String warning, int tokensFreed, int activeTokens) {
  public ContextEditResult {
    messages = List.copyOf(messages);
  }
}
