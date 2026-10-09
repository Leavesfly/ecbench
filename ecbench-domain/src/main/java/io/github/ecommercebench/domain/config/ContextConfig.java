package io.github.ecommercebench.domain.config;

/**
 * 上下文窗口清理参数。
 *
 * @param trigger 触发清理的 token 阈值，计数超过它就启动旧消息淘汰
 * @param clearAtLeast 一次清理至少释放的 token 数，避免反复微量回收
 * @param keepToolUse 从最新往回保留多少组工具调用及其结果（可为 0）
 */
public record ContextConfig(int trigger, int clearAtLeast, int keepToolUse) {

  /** 紧凑构造器：trigger 与 clearAtLeast 必须为正，keepToolUse 允许为 0。 */
  public ContextConfig {
    if (trigger <= 0 || clearAtLeast <= 0 || keepToolUse < 0) {
      throw new IllegalArgumentException("上下文配置必须为正数，keepToolUse 可为 0");
    }
  }

  /** 默认阈值：接近 9 万 token 时触发，每次至少回收 3.4 万，保留最近 2 组工具调用。 */
  public static ContextConfig defaults() {
    return new ContextConfig(90_000, 34_000, 2);
  }
}
