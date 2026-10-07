package io.github.ecommercebench.opponent.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.metrics.NegotiationTracker;
import io.github.ecommercebench.opponent.model.NegotiationAction;
import io.github.ecommercebench.opponent.model.NegotiationDecision;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KernelManagerTest {

  @Test
  void acceptedAgreementIsRecordedOnlyAfterOrderCommit() {
    NegotiationTracker tracker = new NegotiationTracker(new RandomStreams(9L));
    KernelManager manager = manager(tracker);
    var counter =
        manager.processAction(
            "Supplier", new NegotiationAction.Offer("sku", Money.of("30"), 10), 1);
    assertThat(counter.decision()).isEqualTo(NegotiationDecision.OFFER);

    var accepted =
        manager.processAction(
            "Supplier", new NegotiationAction.Accept("sku", counter.price(), 10, "address"), 1);
    assertThat(accepted.decision()).isEqualTo(NegotiationDecision.ACCEPT);
    assertThat(tracker.completed()).isEmpty();

    manager.commitAgreement("Supplier", "sku", 1);
    assertThat(tracker.completed()).hasSize(1);
  }

  @Test
  void rollbackDropsPendingAgreementWithoutInflatingMetrics() {
    NegotiationTracker tracker = new NegotiationTracker(new RandomStreams(9L));
    KernelManager manager = manager(tracker);
    var counter =
        manager.processAction(
            "Supplier", new NegotiationAction.Offer("sku", Money.of("30"), 10), 1);
    manager.processAction(
        "Supplier", new NegotiationAction.Accept("sku", counter.price(), 10, "address"), 1);

    manager.rollbackAgreement("Supplier", "sku");

    assertThat(tracker.completed()).isEmpty();
  }

  private KernelManager manager(NegotiationTracker tracker) {
    Product product =
        new Product(
            "sku",
            "001",
            "cat",
            "beauty",
            "brand",
            "title",
            "Small",
            new BigDecimal("100"),
            new BigDecimal("0.1"));
    Supplier supplier =
        new Supplier(
            "SUP-1",
            "Supplier",
            "s@example.com",
            "good",
            "Friendly",
            0.5,
            null,
            List.of("cat"),
            10);
    CategoryParams params =
        new CategoryParams(
            "cat",
            "beauty",
            "Small",
            BigDecimal.ONE,
            new BigDecimal("200"),
            10,
            20,
            BigDecimal.ZERO,
            BigDecimal.ONE,
            "",
            "linear",
            BigDecimal.ONE,
            new BigDecimal("0.60"),
            new BigDecimal("0.40"),
            new BigDecimal("0.70"));
    CatalogData catalog =
        new CatalogData(
            List.of(product),
            List.of(supplier),
            Map.of("cat", params),
            Map.of(),
            List.of(),
            List.of());
    return new KernelManager(catalog, new SupplierPolicy(), new RandomStreams(4L), tracker);
  }
}
