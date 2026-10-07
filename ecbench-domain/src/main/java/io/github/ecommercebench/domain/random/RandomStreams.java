package io.github.ecommercebench.domain.random;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 按业务用途派生相互隔离的确定性随机流。
 *
 * <p>新增一种随机行为时，不会消耗其他业务流的随机序列，从而避免销售、退货和谈判之间相互影响。
 */
public final class RandomStreams {

  private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;
  private final long rootSeed;

  public RandomStreams(long rootSeed) {
    this.rootSeed = rootSeed;
  }

  public DeterministicRandom stream(String purpose) {
    Objects.requireNonNull(purpose, "purpose 不能为空");
    long purposeHash = fnv1a64(purpose);
    long childSeed = (rootSeed * FNV_PRIME) ^ purposeHash;
    return new DeterministicRandom(childSeed);
  }

  private static long fnv1a64(String value) {
    long hash = FNV_OFFSET_BASIS;
    for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
      hash ^= Byte.toUnsignedInt(current);
      hash *= FNV_PRIME;
    }
    return hash;
  }
}
