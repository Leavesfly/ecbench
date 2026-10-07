package io.github.ecommercebench.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SimulationEngineDiscoveryTest {

  @Test
  void joinsActivePromotionWithinAllowedDiscountRange() {
    SimulationEngine engine = engine();
    String storeId = engine.openStore("beauty", "Beauty").storeId();

    var accepted = engine.joinPromotion(storeId, "New Year Kickoff Sale", new BigDecimal("0.20"));
    var rejected = engine.joinPromotion(storeId, "New Year Kickoff Sale", new BigDecimal("0.01"));

    assertThat(accepted.success()).isTrue();
    assertThat(accepted.activeNow()).isTrue();
    assertThat(rejected.success()).isFalse();
  }

  @Test
  void supplierSearchUsesCatalogData() {
    SimulationEngine engine = engine();

    assertThat(engine.supplierSearch(null, "Personal Care", null))
        .singleElement()
        .satisfies(supplier -> assertThat(supplier.supplierName()).isEqualTo("Sample Supply"));
  }

  @Test
  void returnTraceShowsVisibleSupplierMixWithoutDefectFlag() {
    SimulationEngine engine = engine();
    engine.receivePurchaseOrder("Sample Supply", "sku-1", 10, Money.of("50"), true, 0);

    var trace = engine.traceReturnSources("sku-1");

    assertThat(trace.products())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.totalUnitsDelivered()).isEqualTo(10);
              assertThat(row.supplierSources())
                  .singleElement()
                  .satisfies(source -> assertThat(source.supplier()).isEqualTo("Sample Supply"));
            });
  }

  private SimulationEngine engine() {
    return new SimulationEngine(
        TestFixtures.catalog(), TestFixtures.config(), new RandomStreams(1L));
  }
}
