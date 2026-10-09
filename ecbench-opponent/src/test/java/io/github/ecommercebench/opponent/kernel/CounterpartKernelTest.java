package io.github.ecommercebench.opponent.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.DeterministicRandom;
import io.github.ecommercebench.opponent.model.CounterpartAction;
import io.github.ecommercebench.opponent.model.SupplierFamily;

import java.util.List;

import org.junit.jupiter.api.Test;

class CounterpartKernelTest {

    @Test
    void openingOfferStaysInsideReservationAndMaximum() {
        CounterpartAction opening = kernel(9L).getAction(1, null);

        assertThat(opening.price()).isBetween(Money.of("50"), Money.of("100"));
    }

    @Test
    void sellerCounterNeverFallsBelowAgentOffer() {
        CounterpartKernel kernel = kernel(9L);
        kernel.getAction(1, null);

        CounterpartAction response = kernel.getAction(2, Money.of("70"));

        if (response.price() != null) {
            assertThat(response.price()).isGreaterThanOrEqualTo(Money.of("70"));
        }
    }

    @Test
    void sameSeedProducesSameActionSequence() {
        assertThat(play(kernel(123L))).isEqualTo(play(kernel(123L)));
    }

    private List<CounterpartAction> play(CounterpartKernel kernel) {
        return List.of(
                kernel.getAction(1, null),
                kernel.getAction(2, Money.of("55")),
                kernel.getAction(3, Money.of("60")));
    }

    private CounterpartKernel kernel(long seed) {
        return new CounterpartKernel(
                new KernelParameters(
                        SupplierFamily.CANDID,
                        Money.of("50"),
                        0.65,
                        "neutral",
                        0.50,
                        10,
                        Money.ZERO,
                        Money.of("100")),
                new DeterministicRandom(seed));
    }
}
