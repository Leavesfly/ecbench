package io.github.ecommercebench.simulation.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.state.ShipSpeed;
import org.junit.jupiter.api.Test;

class SimulationStatsTest {

    @Test
    void fraudStatsTrackSpendByType() {
        FraudStats stats = new FraudStats();
        stats.recordSpend("vip_fee", Money.of("1000"));
        stats.recordSpend("vip_fee", Money.of("50"));

        assertThat(stats.spendOnBadSupplier()).isEqualTo(Money.of("1050"));
        assertThat(stats.perType()).containsEntry("vip_fee", Money.of("1050"));
    }

    @Test
    void fulfilmentStatsTrackShippingSpeed() {
        FulfilmentStats stats = new FulfilmentStats();
        stats.recordSold(5);
        stats.recordShipped(ShipSpeed.FAST);
        stats.recordCancelled();

        assertThat(stats.ordersSold()).isEqualTo(1);
        assertThat(stats.unitsSold()).isEqualTo(5);
        assertThat(stats.ordersShipped()).isEqualTo(1);
        assertThat(stats.ordersCancelled()).isEqualTo(1);
        assertThat(stats.shipSpeedCounts()).containsEntry(ShipSpeed.FAST, 1);
    }

    @Test
    void returnStatsTrackReasonBreakdown() {
        ReturnStats stats = new ReturnStats();
        stats.recordActual(2, 1, 1, 0, 0, Money.of("200"), Money.of("10"));

        assertThat(stats.unitsReturned()).isEqualTo(2);
        assertThat(stats.natural()).isEqualTo(1);
        assertThat(stats.price()).isEqualTo(1);
        assertThat(stats.refundLossTotal()).isEqualTo(Money.of("200"));
    }
}
