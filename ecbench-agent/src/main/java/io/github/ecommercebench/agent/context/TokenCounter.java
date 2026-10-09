package io.github.ecommercebench.agent.context;

import io.github.ecommercebench.llm.model.ChatMessage;

/**
 * 计算一条完整聊天消息占用的 token 数。
 */
@FunctionalInterface
public interface TokenCounter {
    /** 返回该消息占用上下文的 token 数；已清除的消息计 0。 */
    int count(ChatMessage message);
}
