package io.github.ecommercebench.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.dto.PublishItem;
import io.github.ecommercebench.simulation.state.ShipSpeed;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class SimulationEngineDailyTest {

    @Test
    void dailyAdvanceChargesCostsAndCreatesPendingShipments() {
        SimulationEngine engine = tradingEngine();

        var daily = engine.advanceToNextDay(LocalDate.parse("2026-01-01"));

        assertThat(daily.day()).isEqualTo(1);
        assertThat(daily.date()).isEqualTo(LocalDate.parse("2026-01-02"));
        assertThat(daily.opsCostCharged()).isEqualTo(Money.of("60"));
        assertThat(daily.storageCharged()).isEqualTo(Money.of("5"));
        assertThat(daily.totalSold()).isPositive();
        assertThat(engine.listPendingShipments()).isNotEmpty();
    }

    @Test
    void shippingChargesBankAndMovesNetRevenueToEscrow() {
        SimulationEngine engine = tradingEngine();
        engine.advanceToNextDay(LocalDate.parse("2026-01-01"));
        Money bankBeforeShipping = engine.checkBalance().bankBalance();
        List<Long> ids = engine.listPendingShipments().stream().map(s -> s.shipmentId()).toList();

        var shipped = engine.shipOrders(ids, ShipSpeed.STANDARD);

        assertThat(shipped.success()).isTrue();
        assertThat(shipped.shippedCount()).isEqualTo(ids.size());
        assertThat(engine.checkBalance().bankBalance()).isLessThan(bankBeforeShipping);
        assertThat(engine.checkBalance().pendingSettlement()).isGreaterThan(Money.ZERO);
    }

    @Test
    void shippedRevenueSettlesAfterNineDays() {
        SimulationEngine engine = tradingEngine();
        engine.advanceToNextDay(LocalDate.parse("2026-01-01"));
        List<Long> ids = engine.listPendingShipments().stream().map(s -> s.shipmentId()).toList();
        engine.shipOrders(ids, ShipSpeed.STANDARD);

        for (int i = 0; i < 9; i++) {
            engine.advanceToNextDay(engine.currentDate());
        }

        assertThat(engine.checkBalance().platformWallet()).isGreaterThan(Money.ZERO);
    }

    @Test
    void delayedPurchaseArrivesOnConfiguredDay() {
        SimulationEngine engine =
                new SimulationEngine(TestFixtures.catalog(), TestFixtures.config(), new RandomStreams(1L));
        engine.receivePurchaseOrder("SUP-1", "sku-1", 10, Money.of("50"), true, 2);

        engine.advanceToNextDay(engine.currentDate());
        assertThat(engine.checkWarehouse()).isEmpty();
        engine.advanceToNextDay(engine.currentDate());

        assertThat(engine.checkWarehouse())
                .singleElement()
                .satisfies(row -> assertThat(row.quantity()).isEqualTo(10));
        assertThat(engine.state().defectiveFraction("sku-1")).isEqualTo(1.0);
    }

    @Test
    void tenNegativeDaysCauseBankruptcy() {
        SimulationEngine engine =
                new SimulationEngine(TestFixtures.catalog(), TestFixtures.config(), new RandomStreams(1L));
        engine.state().accounts().chargeBank(Money.of("100001"));

        for (int i = 0; i < 10; i++) {
            engine.advanceToNextDay(engine.currentDate());
        }

        assertThat(engine.state().terminated()).isTrue();
        assertThat(engine.state().terminationReason()).isEqualTo("bankrupt");
    }

    private SimulationEngine tradingEngine() {
        SimulationEngine engine =
                new SimulationEngine(TestFixtures.catalog(), TestFixtures.config(), new RandomStreams(7L));
        String storeId = engine.openStore("beauty", "Beauty").storeId();
        engine.receivePurchaseOrder("sku-1", 100, Money.of("50"), false, 0);
        engine.publishToStore(storeId, List.of(new PublishItem("sku-1", 100, Money.of("80"))));
        return engine;
    }
}
