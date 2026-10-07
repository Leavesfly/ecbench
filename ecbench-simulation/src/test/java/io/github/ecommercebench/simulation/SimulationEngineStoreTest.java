package io.github.ecommercebench.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.dto.PriceItem;
import io.github.ecommercebench.simulation.dto.PublishItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationEngineStoreTest {

  @Test
  void opensAtMostFourDistinctStoresAndChargesSetupFees() {
    SimulationEngine engine = engine();

    assertThat(engine.openStore("beauty", "Beauty").success()).isTrue();
    assertThat(engine.openStore("fashion", "Fashion").success()).isTrue();
    assertThat(engine.openStore("home_living", "Home").success()).isTrue();
    assertThat(engine.openStore("pet", "Pet").success()).isTrue();
    assertThat(engine.openStore("beauty", "Fifth").success()).isFalse();
    assertThat(engine.checkBalance().bankBalance()).isEqualTo(Money.of("98000"));
  }

  @Test
  void refusesSecondOpenStoreOfSameType() {
    SimulationEngine engine = engine();
    engine.openStore("beauty", "Beauty");

    assertThat(engine.openStore("beauty", "Beauty 2").success()).isFalse();
  }

  @Test
  void publishesStockWithoutRemovingPhysicalWarehouseLot() {
    SimulationEngine engine = engine();
    String storeId = engine.openStore("beauty", "Beauty").storeId();
    engine.receivePurchaseOrder("sku-1", 10, Money.of("50"), false, 0);

    var result =
        engine.publishToStore(storeId, List.of(new PublishItem("sku-1", 5, Money.of("100"))));

    assertThat(result.results())
        .singleElement()
        .satisfies(item -> assertThat(item.success()).isTrue());
    assertThat(engine.checkWarehouse())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.quantity()).isEqualTo(5);
              assertThat(row.physicalQuantity()).isEqualTo(10);
            });
  }

  @Test
  void changesPriceAndReturnsAllocationToWarehouse() {
    SimulationEngine engine = engine();
    String storeId = engine.openStore("beauty", "Beauty").storeId();
    engine.receivePurchaseOrder("sku-1", 10, Money.of("50"), false, 0);
    engine.publishToStore(storeId, List.of(new PublishItem("sku-1", 5, Money.of("100"))));

    assertThat(
            engine.setPrices(storeId, List.of(new PriceItem("sku-1", Money.of("120")))).results())
        .singleElement()
        .satisfies(item -> assertThat(item.newPrice()).isEqualTo(Money.of("120")));
    engine.returnToWarehouse(
        storeId, List.of(new io.github.ecommercebench.simulation.dto.QtyItem("sku-1", 2)));
    assertThat(engine.checkWarehouse().get(0).quantity()).isEqualTo(7);
  }

  @Test
  void withdrawsSettledWalletFunds() {
    SimulationEngine engine = engine();
    engine.state().accounts().creditWallet(Money.of("250"));

    var result = engine.withdraw(null);

    assertThat(result.success()).isTrue();
    assertThat(result.withdrawn()).isEqualTo(Money.of("250"));
    assertThat(result.bankBalance()).isEqualTo(Money.of("100250"));
  }

  private SimulationEngine engine() {
    return new SimulationEngine(
        TestFixtures.catalog(), TestFixtures.config(), new RandomStreams(1L));
  }
}
