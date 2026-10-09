package io.github.ecommercebench.domain.random;

import java.util.SplittableRandom;

/** 在 Java 实现中提供固定且可重复的伪随机数序列。 */
public final class DeterministicRandom {

  private final SplittableRandom delegate;

  /** 以固定 seed 构造；同一 seed 总是产生完全相同的调用序列，是仿真可复现的基石。 */
  public DeterministicRandom(long seed) {
    this.delegate = new SplittableRandom(seed);
  }

  /** 返回 [0.0, 1.0) 上的均匀分布 double。 */
  public double nextDouble() {
    return delegate.nextDouble();
  }

  /** 返回 [0, bound) 上的均匀分布整数。 */
  public int nextInt(int bound) {
    return delegate.nextInt(bound);
  }

  /** 返回一个 long 原始值（多用于再派生子种子）。 */
  public long nextLong() {
    return delegate.nextLong();
  }

  /**
   * 使用 Box-Muller 变换生成标准正态分布值。
   *
   * <p>先把第一个均匀变量抬到 {@code Double.MIN_VALUE} 以上，避免 log(0) 得到无穷大。
   */
  public double nextGaussian() {
    double first = Math.max(delegate.nextDouble(), Double.MIN_VALUE);
    double second = delegate.nextDouble();
    return Math.sqrt(-2.0 * Math.log(first)) * Math.cos(2.0 * Math.PI * second);
  }
}
