package io.github.ecommercebench.llm.http;

import java.time.Duration;

/** 指数退避参数。maxAttempts 表示包含首次调用在内的总尝试次数。 */
public record RetryPolicy(
    int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {
  public RetryPolicy {
    if (maxAttempts < 1 || multiplier < 1.0) {
      throw new IllegalArgumentException("重试次数必须为正数，倍率不得小于 1");
    }
  }

  public static RetryPolicy defaults() {
    return new RetryPolicy(8, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60));
  }
}
