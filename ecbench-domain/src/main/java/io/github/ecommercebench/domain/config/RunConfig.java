package io.github.ecommercebench.domain.config;

import io.github.ecommercebench.domain.money.Money;
import java.nio.file.Path;

/** 单次基准运行的不可变配置。 */
public record RunConfig(
    String modelKey,
    int maxTokens,
    int maxTurns,
    int maxDays,
    Money initialBalance,
    Money dailyFee,
    int maxTokenCapacity,
    Path tokenizerPath,
    Path dataDir,
    Path logDir,
    Path jobFile,
    int runs,
    long seed) {

  public RunConfig {
    if (maxTokens <= 0 || maxTurns <= 0 || maxDays <= 0 || maxTokenCapacity <= 0 || runs <= 0) {
      throw new IllegalArgumentException("运行上限与 runs 必须为正数");
    }
  }

  public static RunConfig defaults() {
    return new RunConfig(
        null,
        16_384,
        4_000,
        365,
        Money.of("100000"),
        Money.of("50"),
        128_000,
        null,
        Path.of("data"),
        null,
        null,
        1,
        42L);
  }
}
