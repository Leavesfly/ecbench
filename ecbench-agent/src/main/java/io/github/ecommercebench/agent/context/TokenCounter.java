package io.github.ecommercebench.agent.context;

import io.github.ecommercebench.llm.model.ChatMessage;

/** 计算一条完整聊天消息占用的 token 数。 */
@FunctionalInterface
public interface TokenCounter {
  int count(ChatMessage message);
}
