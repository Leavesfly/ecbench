package io.github.ecommercebench.simulation.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.error.BusinessRuleException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StoreStateTest {

  @Test
  void newStoreUsesBaselineReputation() {
    StoreState store =
        new StoreState("S1", "beauty", "Beauty One", LocalDate.parse("2026-01-01"), Money.of("45"));

    assertThat(store.reputation()).isEqualTo(0.5);
    assertThat(store.isOpen()).isTrue();
    assertThat(store.inventory()).isEmpty();
  }

  @Test
  void publishAndChangePricePreserveInventory() {
    StoreState store =
        new StoreState("S1", "beauty", "Beauty One", LocalDate.parse("2026-01-01"), Money.of("45"));
    store.publish("sku", 5, Money.of("100"));

    store.setPrice("sku", Money.of("120"));

    assertThat(store.inventory()).containsEntry("sku", 5);
    assertThat(store.prices()).containsEntry("sku", Money.of("120"));
  }

  @Test
  void settingPriceForUnpublishedProductFails() {
    StoreState store =
        new StoreState("S1", "beauty", "Beauty One", LocalDate.parse("2026-01-01"), Money.of("45"));

    assertThatThrownBy(() -> store.setPrice("sku", Money.of("120")))
        .isInstanceOf(BusinessRuleException.class);
  }

  @Test
  void inventoryCannotBecomeNegative() {
    StoreState store =
        new StoreState("S1", "beauty", "Beauty One", LocalDate.parse("2026-01-01"), Money.of("45"));
    store.publish("sku", 2, Money.of("100"));

    assertThatThrownBy(() -> store.removeInventory("sku", 3))
        .isInstanceOf(BusinessRuleException.class);
  }
}
