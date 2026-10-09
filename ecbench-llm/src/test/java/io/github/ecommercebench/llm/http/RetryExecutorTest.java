package io.github.ecommercebench.llm.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.llm.error.ProviderException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RetryExecutorTest {

    private final RetryExecutor executor = new RetryExecutor(duration -> {
    });
    private final RetryPolicy policy = new RetryPolicy(8, Duration.ZERO, 2.0, Duration.ZERO);

    @Test
    void retriesTransientFailuresUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();

        String result =
                executor.execute(
                        () -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new ProviderException("openai", 429, true, "rate limited");
                            }
                            return "ok";
                        },
                        policy);

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void permanentFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(
                () ->
                        executor.execute(
                                () -> {
                                    attempts.incrementAndGet();
                                    throw new ProviderException("openai", 401, false, "unauthorized");
                                },
                                policy))
                .isInstanceOf(ProviderException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void stopsAfterMaximumAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(
                () ->
                        executor.execute(
                                () -> {
                                    attempts.incrementAndGet();
                                    throw new ProviderException("openai", 500, true, "server error");
                                },
                                policy))
                .isInstanceOf(ProviderException.class);
        assertThat(attempts).hasValue(8);
    }
}
