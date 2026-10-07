package io.github.ecommercebench.domain.config;

/** 上下文窗口清理参数。 */
public record ContextConfig(int trigger, int clearAtLeast, int keepToolUse) {

  public ContextConfig {
    if (trigger <= 0 || clearAtLeast <= 0 || keepToolUse < 0) {
      throw new IllegalArgumentException("上下文配置必须为正数，keepToolUse 可为 0");
    }
  }

  public static ContextConfig defaults() {
    return new ContextConfig(90_000, 34_000, 2);
  }
}
