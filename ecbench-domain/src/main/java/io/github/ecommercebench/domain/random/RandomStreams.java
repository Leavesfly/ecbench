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

  /**
   * 按业务用途派生一个相互隔离的子随机流。
   *
   * <p>子种子由根种子与用途名的 FNV-1a 哈希共同决定，因此不同用途（销售、退货、谈判等） 拥有各自独立、互不消耗的序列，新增一类随机行为不会改变其他流的既有结果。
   */
  public DeterministicRandom stream(String purpose) {
    Objects.requireNonNull(purpose, "purpose 不能为空");
    long purposeHash = fnv1a64(purpose);
    long childSeed = (rootSeed * FNV_PRIME) ^ purposeHash;
    return new DeterministicRandom(childSeed);
  }

  /** FNV-1a 64 位哈希，将用途字符串稳定映射为一个 long，用于派生子种子。 */
  private static long fnv1a64(String value) {
    long hash = FNV_OFFSET_BASIS;
    for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
      hash ^= Byte.toUnsignedInt(current);
      hash *= FNV_PRIME;
    }
    return hash;
  }
}
