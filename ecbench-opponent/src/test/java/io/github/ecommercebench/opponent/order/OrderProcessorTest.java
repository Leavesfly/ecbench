package io.github.ecommercebench.opponent.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.model.FraudType;
import io.github.ecommercebench.opponent.model.NegotiationAction;
import io.github.ecommercebench.opponent.model.NegotiationDecision;
import io.github.ecommercebench.opponent.model.NegotiationOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderProcessorTest {

  @Test
  void quantityBaitChargesOrderedQuantityButSchedulesOnlySixtyToSeventyPercent() {
    FakePort port = new FakePort(Money.of("10000"));
    OrderProcessor processor = processor(FraudType.QTY_BAIT);

    var result =
        processor.processStructuredOrder(
            badSupplier(FraudType.QTY_BAIT),
            new NegotiationAction.Accept("sku", Money.of("50"), 10, "address"),
            accepted("50"),
            port);

    assertThat(result.confirmed()).isTrue();
    assertThat(result.chargedAmount()).isEqualTo(Money.of("500"));
    assertThat(result.deliveredQuantity()).isBetween(6, 7);
    assertThat(port.deliveries)
        .singleElement()
        .satisfies(delivery -> assertThat(delivery.quantity()).isBetween(6, 7));
  }

  @Test
  void qualityDowngradeMarksDeliveryDefective() {
    FakePort port = new FakePort(Money.of("10000"));
    var result =
        processor(FraudType.QUALITY_DOWNGRADE)
            .processStructuredOrder(
                badSupplier(FraudType.QUALITY_DOWNGRADE), accept10(), accepted("50"), port);

    assertThat(result.defective()).isTrue();
    assertThat(port.deliveries.get(0).defective()).isTrue();
  }

  @Test
  void vipSupplierRejectsProductOrderUntilFeePaid() {
    FakePort port = new FakePort(Money.of("10000"));
    OrderProcessor processor = processor(FraudType.VIP_FEE);

    var result =
        processor.processStructuredOrder(
            badSupplier(FraudType.VIP_FEE), accept10(), accepted("50"), port);

    assertThat(result.confirmed()).isFalse();
    assertThat(port.balance).isEqualTo(Money.of("10000"));
  }

  @Test
  void insufficientFundsDoNotScheduleDelivery() {
    FakePort port = new FakePort(Money.of("100"));
    var result =
        processor(FraudType.NONE)
            .processStructuredOrder(goodSupplier(), accept10(), accepted("50"), port);

    assertThat(result.confirmed()).isFalse();
    assertThat(port.deliveries).isEmpty();
  }

  @Test
  void payVipFeeSucceedsMarksSupplierPaidAndUnblocksOrder() {
    FakePort port = new FakePort(Money.of("10000"));
    OrderProcessor processor = processor(FraudType.VIP_FEE);
    Supplier supplier = badSupplier(FraudType.VIP_FEE);

    var fee = processor.payVipFee(supplier, port);

    assertThat(fee.confirmed()).isTrue();
    assertThat(processor.hasPaidVip(supplier.supplierName())).isTrue();
    assertThat(port.balance).isLessThan(Money.of("10000"));
    // 付费后同一供应商的商品订单应成功
    var order = processor.processStructuredOrder(supplier, accept10(), accepted("50"), port);
    assertThat(order.confirmed()).isTrue();
  }

  @Test
  void payVipFeeFailsOnInsufficientFunds() {
    FakePort port = new FakePort(Money.of("1"));

    var fee = processor(FraudType.VIP_FEE).payVipFee(badSupplier(FraudType.VIP_FEE), port);

    assertThat(fee.confirmed()).isFalse();
    assertThat(port.balance).isEqualTo(Money.of("1"));
  }

  @Test
  void unknownSkuFailsOrder() {
    FakePort port = new FakePort(Money.of("10000"));

    var result =
        processor(FraudType.NONE)
            .processStructuredOrder(
                goodSupplier(),
                new NegotiationAction.Accept("missing-sku", Money.of("50"), 10, "address"),
                accepted("50"),
                port);

    assertThat(result.confirmed()).isFalse();
    assertThat(port.deliveries).isEmpty();
  }

  @Test
  void nonPositiveQuantityFailsOrder() {
    FakePort port = new FakePort(Money.of("10000"));

    var result =
        processor(FraudType.NONE)
            .processStructuredOrder(
                goodSupplier(),
                new NegotiationAction.Accept("sku", Money.of("50"), 0, "address"),
                accepted("50"),
                port);

    assertThat(result.confirmed()).isFalse();
  }

  private OrderProcessor processor(FraudType fraudType) {
    return new OrderProcessor(
        catalog(badSupplier(fraudType)), new SupplierPolicy(), new RandomStreams(3L));
  }

  private NegotiationAction.Accept accept10() {
    return new NegotiationAction.Accept("sku", Money.of("50"), 10, "address");
  }

  private NegotiationOutcome accepted(String price) {
    return new NegotiationOutcome(
        "sku",
        "title",
        NegotiationDecision.ACCEPT,
        Money.of(price),
        Money.of(price),
        2,
        "positive",
        "Concede",
        null,
        null,
        true);
  }

  private Supplier goodSupplier() {
    return new Supplier(
        "SUP-G", "Good", "g@example.com", "good", "Friendly", 0.5, null, List.of("cat"), 10);
  }

  private Supplier badSupplier(FraudType type) {
    if (type == FraudType.NONE) {
      return goodSupplier();
    }
    return new Supplier(
        "SUP-B",
        "Bad",
        "b@example.com",
        "bad",
        "Adversarial",
        0.5,
        type.wireName(),
        List.of("cat"),
        10);
  }

  private CatalogData catalog(Supplier supplier) {
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
    return new CatalogData(
        List.of(product), List.of(supplier), Map.of("cat", params), Map.of(), List.of(), List.of());
  }

  private static final class FakePort implements OrderExecutionPort {
    private Money balance;
    private final List<ScheduledDelivery> deliveries = new ArrayList<>();

    private FakePort(Money balance) {
      this.balance = balance;
    }

    @Override
    public Money bankBalance() {
      return balance;
    }

    @Override
    public void debitBank(Money amount) {
      balance = balance.subtract(amount);
    }

    @Override
    public void scheduleDelivery(ScheduledDelivery delivery) {
      deliveries.add(delivery);
    }

    @Override
    public void recordFraudSpend(FraudType type, Money amount) {}
  }
}
