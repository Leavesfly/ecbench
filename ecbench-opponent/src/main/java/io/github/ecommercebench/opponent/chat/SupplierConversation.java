package io.github.ecommercebench.opponent.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个供应商的会话、成交计数与破产状态。
 */
public final class SupplierConversation {

    private final List<Message> messages = new ArrayList<>();
    private int orderCount;
    private boolean bankrupt;

    public void append(String role, String content, Instant timestamp) {
        messages.add(new Message(role, content, timestamp));
    }

    public List<Message> history(int limit) {
        int safeLimit = Math.max(0, limit);
        int start = Math.max(0, messages.size() - safeLimit);
        return List.copyOf(messages.subList(start, messages.size()));
    }

    public void recordOrder() {
        orderCount++;
    }

    public void markBankrupt() {
        bankrupt = true;
    }

    public int orderCount() {
        return orderCount;
    }

    public boolean bankrupt() {
        return bankrupt;
    }

    /**
     * 一条供应商会话消息。
     */
    public record Message(String role, String content, Instant timestamp) {
    }
}
