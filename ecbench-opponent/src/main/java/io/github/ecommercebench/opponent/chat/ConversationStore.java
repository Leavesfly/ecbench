package io.github.ecommercebench.opponent.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前 run 内的供应商会话存储，不跨 run 共享。
 */
public final class ConversationStore {

    private final Map<String, SupplierConversation> conversations = new LinkedHashMap<>();

    /** 向指定供应商会话追加一条带时间戳的消息。 */
    public void append(String supplierId, String role, String content, Instant timestamp) {
        conversation(supplierId).append(role, content, timestamp);
    }

    /** 读取指定供应商最近 limit 条会话历史。 */
    public List<SupplierConversation.Message> history(String supplierId, int limit) {
        return conversation(supplierId).history(limit);
    }

    /** 记录与该供应商的一次成交。 */
    public void recordOrder(String supplierId) {
        conversation(supplierId).recordOrder();
    }

    /** 返回与该供应商的累计成交次数。 */
    public int orderCount(String supplierId) {
        return conversation(supplierId).orderCount();
    }

    /** 标记该供应商对应会话为破产。 */
    public void markBankrupt(String supplierId) {
        conversation(supplierId).markBankrupt();
    }

    /** 查询该供应商会话是否已破产。 */
    public boolean isBankrupt(String supplierId) {
        return conversation(supplierId).bankrupt();
    }

    /** 取得或惰性创建供应商会话。 */
    private SupplierConversation conversation(String supplierId) {
        return conversations.computeIfAbsent(supplierId, ignored -> new SupplierConversation());
    }
}
