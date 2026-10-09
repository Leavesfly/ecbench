package io.github.ecommercebench.simulation.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import org.junit.jupiter.api.Test;

/**
 * FraudStats 订单聚合口径测试：订单数/总支出/坑供应商订单/按类型件数/好供应商按人格支出，且不与 recordSpend 重复计支出。
 */
class FraudStatsTest {

    @Test
    void recordsOrderAggregatesForBadAndGoodSuppliers() {
        FraudStats stats = new FraudStats();
        stats.recordOrder(true, "quality_downgrade", null, 10, Money.of("500"));
        stats.recordOrder(false, null, "Friendly", 5, Money.of("200"));
        stats.recordVipSpend(Money.of("1000"));

        assertThat(stats.ordersTotal()).isEqualTo(2);
        assertThat(stats.ordersFromBadSupplier()).isEqualTo(1);
        assertThat(stats.spendTotal()).isEqualTo(Money.of("1700"));
        assertThat(stats.vipFeePaidAmount()).isEqualTo(Money.of("1000"));
        assertThat(stats.perTypeOrders()).containsEntry("quality_downgrade", 1);
        assertThat(stats.perTypeUnits()).containsEntry("quality_downgrade", 10);
        assertThat(stats.spendByPersonality()).containsEntry("Friendly", Money.of("200"));
    }

    @Test
    void recordOrderLeavesSpendOnBadSupplierToRecordSpend() {
        FraudStats stats = new FraudStats();
        stats.recordOrder(true, "qty_bait", null, 3, Money.of("300"));

        // spend_on_bad_supplier 仍由 recordSpend 独立记账，recordOrder 不重复计入
        assertThat(stats.spendOnBadSupplier()).isEqualTo(Money.ZERO);
        assertThat(stats.spendTotal()).isEqualTo(Money.of("300"));
    }
}
