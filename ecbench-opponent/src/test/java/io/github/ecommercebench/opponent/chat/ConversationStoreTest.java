package io.github.ecommercebench.opponent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationStoreTest {

  @Test
  void returnsNewestLimitedMessagesInChronologicalOrder() {
    ConversationStore store = new ConversationStore();
    store.append("SUP-1", "user", "one", Instant.parse("2026-01-01T00:00:00Z"));
    store.append("SUP-1", "assistant", "two", Instant.parse("2026-01-01T00:01:00Z"));
    store.append("SUP-1", "user", "three", Instant.parse("2026-01-01T00:02:00Z"));

    assertThat(store.history("SUP-1", 2))
        .extracting(SupplierConversation.Message::content)
        .containsExactly("two", "three");
  }

  @Test
  void tracksOrderCountAndBankruptcy() {
    ConversationStore store = new ConversationStore();
    store.recordOrder("SUP-1");
    store.markBankrupt("SUP-1");

    assertThat(store.orderCount("SUP-1")).isEqualTo(1);
    assertThat(store.isBankrupt("SUP-1")).isTrue();
  }
}
