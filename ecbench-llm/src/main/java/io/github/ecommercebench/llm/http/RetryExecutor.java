package io.github.ecommercebench.llm.http;

import io.github.ecommercebench.llm.error.ProviderException;
import java.time.Duration;

/** 对可重试 Provider 错误执行有上限的指数退避。 */
public final class RetryExecutor {

  private final Sleeper sleeper;

  public RetryExecutor(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  public <T> T execute(CheckedSupplier<T> operation, RetryPolicy policy) {
    Duration delay = policy.initialDelay();
    for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
      try {
        return operation.get();
      } catch (ProviderException exception) {
        if (!exception.retryable() || attempt == policy.maxAttempts()) {
          throw exception;
        }
        sleep(delay, exception.provider());
        long nextMillis = Math.round(delay.toMillis() * policy.multiplier());
        delay = Duration.ofMillis(Math.min(nextMillis, policy.maxDelay().toMillis()));
      } catch (Exception exception) {
        throw new ProviderException("unknown", false, "Provider 调用失败", exception);
      }
    }
    throw new IllegalStateException("不可达的重试状态");
  }

  private void sleep(Duration delay, String provider) {
    try {
      sleeper.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ProviderException(provider, false, "重试等待被中断", exception);
    }
  }
}
