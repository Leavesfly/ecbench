package io.github.ecommercebench.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.dto.PublishItem;
import io.github.ecommercebench.simulation.state.ShipSpeed;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 覆盖率补强测试：驱动退货处理（ReturnProcessor）、店铺关闭清算、店铺状态、促销加入与店铺列表等既有测试未覆盖的引擎路径。 使用 TestFixtures
 * 的小目录，断言聚焦状态变化与产物非空。
 */
class SimulationEngineCoverageTest {

    private SimulationEngine engine(long seed) {
        return new SimulationEngine(
                TestFixtures.catalog(), TestFixtures.config(), new RandomStreams(seed));
    }

    private SimulationEngine tradingEngine(long seed, boolean defective) {
        SimulationEngine engine = engine(seed);
        String storeId = engine.openStore("beauty", "Beauty").storeId();
        engine.receivePurchaseOrder("SUP-1", "sku-1", 100, Money.of("50"), defective, 0);
        engine.publishToStore(storeId, List.of(new PublishItem("sku-1", 100, Money.of("80"))));
        return engine;
    }

    @Test
    void defectiveSalesShipAndProduceProcessedReturns() {
        SimulationEngine engine = tradingEngine(7L, true);

        engine.advanceToNextDay(engine.currentDate());
        List<Long> ids = engine.listPendingShipments().stream().map(s -> s.shipmentId()).toList();
        assertThat(ids).isNotEmpty();
        engine.shipOrders(ids, ShipSpeed.STANDARD);
        assertThat(engine.state().returnStats().unitsShipped()).isPositive();

        // 推进足够天数让退货到达并被 ReturnProcessor 处理（到货滞后 3-7 天）
        for (int i = 0; i < 12 && !engine.state().terminated(); i++) {
            engine.advanceToNextDay(engine.currentDate());
            List<Long> more = engine.listPendingShipments().stream().map(s -> s.shipmentId()).toList();
            if (!more.isEmpty()) {
                engine.shipOrders(more, ShipSpeed.STANDARD);
            }
        }

        assertThat(engine.state().returnStats().unitsReturned()).isPositive();
        assertThat(engine.state().returnStats().refundLossTotal()).isGreaterThan(Money.ZERO);
        assertThat(engine.state().fulfilmentStats().unitsSold()).isPositive();
    }

    @Test
    void closesStoreWithLiquidationReturningStock() {
        SimulationEngine engine = tradingEngine(3L, false);
        String storeId = engine.listStores().get(0).storeId();

        var result = engine.closeStore(storeId, true);

        assertThat(result).isNotNull();
        assertThat(engine.state().openStoreCount()).isZero();
    }

    @Test
    void reportsStoreStatusAndListStores() {
        SimulationEngine engine = tradingEngine(5L, false);
        String storeId = engine.listStores().get(0).storeId();

        assertThat(engine.listStores()).hasSize(1);
        assertThat(engine.storeStatus(storeId)).isNotNull();
    }

    @Test
    void joinsActivePromotion() {
        SimulationEngine engine = tradingEngine(9L, false);
        String storeId = engine.listStores().get(0).storeId();

        // 2026-01-01 处于 "New Year Kickoff Sale"（01-01..01-07）活动期内
        var result = engine.joinPromotion(storeId, "New Year Kickoff Sale", new BigDecimal("0.20"));

        assertThat(result).isNotNull();
    }

    @Test
    void warehouseAndBalanceViewsArePopulated() {
        SimulationEngine engine = tradingEngine(11L, false);

        assertThat(engine.checkWarehouse()).isNotEmpty();
        assertThat(engine.checkBalance().total()).isGreaterThan(Money.ZERO);
        assertThat(engine.listStores()).hasSize(1);
    }
}
