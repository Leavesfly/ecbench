package io.github.ecommercebench.domain.random;

import java.util.SplittableRandom;

/** 在 Java 实现中提供固定且可重复的伪随机数序列。 */
public final class DeterministicRandom {

  private final SplittableRandom delegate;

  public DeterministicRandom(long seed) {
    this.delegate = new SplittableRandom(seed);
  }

  public double nextDouble() {
    return delegate.nextDouble();
  }

  public int nextInt(int bound) {
    return delegate.nextInt(bound);
  }

  public long nextLong() {
    return delegate.nextLong();
  }

  /** 使用 Box-Muller 变换生成标准正态分布值。 */
  public double nextGaussian() {
    double first = Math.max(delegate.nextDouble(), Double.MIN_VALUE);
    double second = delegate.nextDouble();
    return Math.sqrt(-2.0 * Math.log(first)) * Math.cos(2.0 * Math.PI * second);
  }
}
