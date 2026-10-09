package io.github.ecommercebench.simulation.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class AccountsTest {

    @Test
    void maturedEscrowMovesToWallet() {
        Accounts accounts = new Accounts(Money.of("100000"));
        accounts.addEscrow(new EscrowBatch(1L, Money.of("980"), LocalDate.parse("2026-01-10"), "S1"));

        assertThat(accounts.settleMatured(LocalDate.parse("2026-01-09"))).isEqualTo(Money.ZERO);
        assertThat(accounts.settleMatured(LocalDate.parse("2026-01-10"))).isEqualTo(Money.of("980"));
        assertThat(accounts.wallet()).isEqualTo(Money.of("980"));
        assertThat(accounts.pendingSettlement()).isEqualTo(Money.ZERO);
    }

    @Test
    void refundUsesNamedEscrowBatchBeforeBank() {
        Accounts accounts = new Accounts(Money.of("1000"));
        accounts.addEscrow(new EscrowBatch(7L, Money.of("500"), LocalDate.parse("2026-01-10"), "S1"));

        accounts.refund(7L, Money.of("700"));

        assertThat(accounts.pendingSettlement()).isEqualTo(Money.ZERO);
        assertThat(accounts.bank()).isEqualTo(Money.of("1000"));
        assertThat(accounts.commissionReversed()).isEqualTo(Money.of("200"));
    }

    @Test
    void withdrawalMovesAvailableWalletToBank() {
        Accounts accounts = new Accounts(Money.of("1000"));
        accounts.creditWallet(Money.of("250"));

        Money transferred = accounts.withdraw(null);

        assertThat(transferred).isEqualTo(Money.of("250"));
        assertThat(accounts.bank()).isEqualTo(Money.of("1250"));
        assertThat(accounts.wallet()).isEqualTo(Money.ZERO);
    }

    @Test
    void negativeStreakResetsWhenBankRecovers() {
        Accounts accounts = new Accounts(Money.of("10"));
        accounts.chargeBank(Money.of("20"));
        accounts.updateNegativeStreak();
        accounts.updateNegativeStreak();
        assertThat(accounts.consecutiveNegativeDays()).isEqualTo(2);

        accounts.creditBank(Money.of("20"));
        accounts.updateNegativeStreak();
        assertThat(accounts.consecutiveNegativeDays()).isZero();
    }
}
