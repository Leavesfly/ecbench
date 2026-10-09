package io.github.ecommercebench.opponent.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.model.SupplierFamily;
import org.junit.jupiter.api.Test;

class NegotiationTrackerTest {

    @Test
    void activeNegotiationsAreExcludedFromMetrics() {
        NegotiationTracker tracker = new NegotiationTracker(new RandomStreams(1L));
        tracker.getOrCreate(
                "Good",
                "sku",
                "good",
                SupplierFamily.CANDID,
                Money.of("100"),
                Money.of("40"),
                Money.of("60"),
                1);

        assertThat(tracker.aggregate().completedNegotiations()).isZero();
        assertThat(tracker.aggregate().agrPlus()).isNull();
    }

    @Test
    void separatesGoodAgreementRateAndFalseBadAgreementRate() {
        NegotiationTracker tracker = new NegotiationTracker(new RandomStreams(1L));
        complete(tracker, "Good", "sku-1", "good", "Agreement", Money.of("60"));
        complete(tracker, "Bad", "sku-2", "bad", "Agreement", Money.of("70"));
        complete(tracker, "Bad", "sku-3", "bad", "Disagreement", null);

        NegotiationMetrics metrics = tracker.aggregate();

        assertThat(metrics.agrPlus()).isEqualTo(1.0);
        assertThat(metrics.fagrMinus()).isEqualTo(0.5);
        assertThat(metrics.sePlus()).isCloseTo(2.0 / 3.0, within(0.000001));
    }

    @Test
    void detectsBuyerOfferViolations() {
        NegotiationTracker tracker = new NegotiationTracker(new RandomStreams(1L));
        tracker.getOrCreate(
                "Good",
                "sku",
                "good",
                SupplierFamily.CANDID,
                Money.of("100"),
                Money.of("40"),
                Money.of("60"),
                1);
        tracker.recordAgentOffer("Good", "sku", Money.of("160"));
        tracker.recordOutcome("Good", "sku", "Disagreement", null, "AgentReject", 1);

        assertThat(tracker.aggregate().criticalViolationRate()).isEqualTo(1.0);
    }

    @Test
    void aggregatesRoundsToDealAndMoneySavedVsInitial() {
        NegotiationTracker tracker = new NegotiationTracker(new RandomStreams(1L));
        // initialOffer=60；两笔成交 finalPrice 50 与 55 → saved 10 + 5 = 15；每笔 complete() 记 1 轮
        complete(tracker, "Good", "sku-1", "good", "Agreement", Money.of("50"));
        complete(tracker, "Good", "sku-2", "good", "Agreement", Money.of("55"));
        complete(tracker, "Good", "sku-3", "good", "Disagreement", null);

        NegotiationMetrics metrics = tracker.aggregate();

        assertThat(metrics.totalMoneySavedVsInitial()).isEqualTo(15.0);
        assertThat(metrics.avgRoundsToDeal()).isEqualTo(1.0);
    }

    private void complete(
            NegotiationTracker tracker,
            String supplier,
            String sku,
            String type,
            String outcome,
            Money price) {
        tracker.getOrCreate(
                supplier,
                sku,
                type,
                SupplierFamily.CANDID,
                Money.of("100"),
                Money.of("40"),
                Money.of("60"),
                1);
        tracker.recordOutcome(supplier, sku, outcome, price, "test", 1);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
