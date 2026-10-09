package io.github.ecommercebench.domain.random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RandomStreamsTest {

    @Test
    void sameRootAndPurposeProduceSameSequence() {
        DeterministicRandom first = new RandomStreams(42L).stream("sales");
        DeterministicRandom second = new RandomStreams(42L).stream("sales");

        assertThat(first.nextDouble()).isEqualTo(second.nextDouble());
        assertThat(first.nextInt(100)).isEqualTo(second.nextInt(100));
        assertThat(first.nextLong()).isEqualTo(second.nextLong());
    }

    @Test
    void differentPurposesProduceIndependentSequences() {
        RandomStreams streams = new RandomStreams(42L);

        assertThat(streams.stream("sales").nextLong())
                .isNotEqualTo(streams.stream("returns").nextLong());
    }

    @Test
    void requestingSamePurposeReturnsFreshRepeatableStream() {
        RandomStreams streams = new RandomStreams(7L);
        long first = streams.stream("negotiation").nextLong();
        long replay = streams.stream("negotiation").nextLong();

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void boundMustBePositive() {
        DeterministicRandom random = new RandomStreams(1L).stream("test");

        assertThatThrownBy(() -> random.nextInt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
