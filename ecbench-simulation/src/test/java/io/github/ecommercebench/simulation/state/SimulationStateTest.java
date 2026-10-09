package io.github.ecommercebench.simulation.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SimulationStateTest {

    @Test
    void initialStateHasExpectedBusinessDefaults() {
        SimulationState state =
                SimulationState.initial(Money.of("100000"), LocalDate.parse("2026-01-01"));

        assertThat(state.currentDate()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(state.accounts().bank()).isEqualTo(Money.of("100000"));
        assertThat(state.openStoreCount()).isZero();
        assertThat(state.maxStores()).isEqualTo(4);
    }

    @Test
    void totalAssetsIncludesAllThreeMoneyBuckets() {
        SimulationState state =
                SimulationState.initial(Money.of("100000"), LocalDate.parse("2026-01-01"));
        state.accounts().creditWallet(Money.of("250"));
        state
                .accounts()
                .addEscrow(new EscrowBatch(1L, Money.of("500"), LocalDate.parse("2026-01-10"), "S1"));

        assertThat(state.totalAssets()).isEqualTo(Money.of("100750"));
    }

    @Test
    void identifiersAreMonotonicAndIndependent() {
        SimulationState state =
                SimulationState.initial(Money.of("100000"), LocalDate.parse("2026-01-01"));

        assertThat(state.nextStoreId()).isEqualTo("store_001");
        assertThat(state.nextStoreId()).isEqualTo("store_002");
        assertThat(state.nextOrderId()).isEqualTo(1L);
        assertThat(state.nextOrderId()).isEqualTo(2L);
        assertThat(state.nextBatchId()).isEqualTo(1L);
    }
}
