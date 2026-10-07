package io.github.ecommercebench.opponent.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.metrics.NegotiationTracker;
import io.github.ecommercebench.opponent.model.FraudType;
import io.github.ecommercebench.opponent.model.NegotiationAction;
import io.github.ecommercebench.opponent.model.NegotiationDecision;
import io.github.ecommercebench.opponent.model.SupplierFamily;
import io.github.ecommercebench.opponent.parser.NegotiationBlockParser;
import io.github.ecommercebench.opponent.scam.ScamHandler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 覆盖率补强测试：ScamHandler、SupplierFamily、NegotiationDecision 全枚举，以及 KernelManager 的拒绝/错误/高价成交路径。 */
class OpponentCoverageTest {

  @Test
  void scamHandlerDescribesEveryFraudType() {
    ScamHandler handler = new ScamHandler();
    assertThat(handler.requiresVipFee(FraudType.VIP_FEE)).isTrue();
    assertThat(handler.requiresVipFee(FraudType.QTY_BAIT)).isFalse();
    assertThat(handler.isMembershipFeeItem("MEMBERSHIP_FEE")).isTrue();
    assertThat(handler.isMembershipFeeItem("membership_fee")).isTrue();
    assertThat(handler.isMembershipFeeItem("sku-1")).isFalse();
    for (FraudType type : FraudType.values()) {
      assertThat(handler.instructions(type)).isNotBlank();
    }
  }

  @Test
  void supplierFamilyMapsEveryPersonality() {
    assertThat(SupplierFamily.fromPersonality(null)).isEqualTo(SupplierFamily.CANDID);
    assertThat(SupplierFamily.fromPersonality("Friendly")).isEqualTo(SupplierFamily.CANDID);
    assertThat(SupplierFamily.fromPersonality("Professional")).isEqualTo(SupplierFamily.TACITURN);
    assertThat(SupplierFamily.fromPersonality("Enthusiastic")).isEqualTo(SupplierFamily.EXPRESSIVE);
    assertThat(SupplierFamily.fromPersonality("Strategic")).isEqualTo(SupplierFamily.STRATEGIC);
    assertThat(SupplierFamily.fromPersonality("Unpredictable"))
        .isEqualTo(SupplierFamily.STOCHASTIC);
    assertThat(SupplierFamily.fromPersonality("Tough")).isEqualTo(SupplierFamily.ADVERSARIAL);
    assertThat(SupplierFamily.fromPersonality("Adversarial")).isEqualTo(SupplierFamily.ADVERSARIAL);
    assertThat(SupplierFamily.fromPersonality("Other")).isEqualTo(SupplierFamily.CANDID);
    for (SupplierFamily family : SupplierFamily.values()) {
      assertThat(family.displayName()).isNotBlank();
    }
  }

  @Test
  void negotiationDecisionExposesCapitalizedWireNames() {
    assertThat(NegotiationDecision.OFFER.wireName()).isEqualTo("Offer");
    assertThat(NegotiationDecision.ACCEPT.wireName()).isEqualTo("Accept");
    assertThat(NegotiationDecision.REJECT.wireName()).isEqualTo("Reject");
    assertThat(NegotiationDecision.ERROR.wireName()).isEqualTo("Error");
  }

  @Test
  void kernelErrorsOnUnknownSupplierOrSku() {
    KernelManager manager = manager(new NegotiationTracker(new RandomStreams(9L)));

    var unknownSku =
        manager.processAction(
            "Supplier", new NegotiationAction.Offer("nope", Money.of("30"), 10), 1);
    var unknownSupplier =
        manager.processAction("Ghost", new NegotiationAction.Offer("sku", Money.of("30"), 10), 1);

    assertThat(unknownSku.decision()).isEqualTo(NegotiationDecision.ERROR);
    assertThat(unknownSupplier.decision()).isEqualTo(NegotiationDecision.ERROR);
  }

  @Test
  void kernelAcceptsVeryHighOfferAndHandlesReject() {
    NegotiationTracker tracker = new NegotiationTracker(new RandomStreams(9L));
    KernelManager manager = manager(tracker);

    var accepted =
        manager.processAction(
            "Supplier", new NegotiationAction.Offer("sku", Money.of("100000"), 10), 1);
    assertThat(accepted.decision()).isEqualTo(NegotiationDecision.ACCEPT);

    var rejected = manager.processAction("Supplier", new NegotiationAction.Reject("sku", 10), 1);
    assertThat(rejected).isNotNull();
    assertThat(rejected.decision()).isNotNull();
  }

  @Test
  void kernelCountersRepeatedLowOffers() {
    KernelManager manager = manager(new NegotiationTracker(new RandomStreams(9L)));
    for (int i = 0; i < 3; i++) {
      var response =
          manager.processAction(
              "Supplier", new NegotiationAction.Offer("sku", Money.of("5"), 10), 1);
      assertThat(response.decision()).isIn(NegotiationDecision.OFFER, NegotiationDecision.REJECT);
    }
  }

  @Test
  void parserHandlesRejectArrayAndDropsIncompleteActions() {
    NegotiationBlockParser parser = new NegotiationBlockParser(new ObjectMapper());

    var reject =
        parser.parse(
            "```negotiate\n{\"action\":\"reject\",\"sku_id\":\"sku\",\"quantity\":2}\n```");
    assertThat(reject.actions()).hasSize(1);

    var array =
        parser.parse(
            "```negotiate\n[{\"action\":\"offer\",\"sku_id\":\"a\",\"price\":10},"
                + "{\"action\":\"offer\",\"sku_id\":\"b\",\"price\":20}]\n```");
    assertThat(array.actions()).hasSize(2);

    // offer 缺 price → 丢弃；缺 sku_id → 丢弃
    assertThat(parser.parse("```negotiate\n{\"action\":\"offer\",\"sku_id\":\"a\"}\n```").actions())
        .isEmpty();
    assertThat(parser.parse("```negotiate\n{\"action\":\"offer\",\"price\":5}\n```").actions())
        .isEmpty();
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
