package io.github.ecommercebench.llm.http;

import java.time.Duration;

/**
 * 指数退避参数。maxAttempts 表示包含首次调用在内的总尝试次数。
 */
public record RetryPolicy(
        int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {
    /** 紧凑构造器：总尝试次数至少 1，退避倍率不得小于 1（否则间隔会越重试越短）。 */
    public RetryPolicy {
        if (maxAttempts < 1 || multiplier < 1.0) {
            throw new IllegalArgumentException("重试次数必须为正数，倍率不得小于 1");
        }
    }

    /** 默认策略：最多 8 次（含首次），首延迟 1s、每次翻倍、上限 60s。 */
    public static RetryPolicy defaults() {
        return new RetryPolicy(8, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60));
    }
}
