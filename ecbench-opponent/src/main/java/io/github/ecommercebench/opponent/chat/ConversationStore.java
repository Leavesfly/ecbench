package io.github.ecommercebench.opponent.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前 run 内的供应商会话存储，不跨 run 共享。 */
public final class ConversationStore {

  private final Map<String, SupplierConversation> conversations = new LinkedHashMap<>();

  public void append(String supplierId, String role, String content, Instant timestamp) {
    conversation(supplierId).append(role, content, timestamp);
  }

  public List<SupplierConversation.Message> history(String supplierId, int limit) {
    return conversation(supplierId).history(limit);
  }

  public void recordOrder(String supplierId) {
    conversation(supplierId).recordOrder();
  }

  public int orderCount(String supplierId) {
    return conversation(supplierId).orderCount();
  }

  public void markBankrupt(String supplierId) {
    conversation(supplierId).markBankrupt();
  }

  public boolean isBankrupt(String supplierId) {
    return conversation(supplierId).bankrupt();
  }

  private SupplierConversation conversation(String supplierId) {
    return conversations.computeIfAbsent(supplierId, ignored -> new SupplierConversation());
  }
}
