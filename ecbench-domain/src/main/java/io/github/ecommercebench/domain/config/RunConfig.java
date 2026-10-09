package io.github.ecommercebench.domain.config;

import io.github.ecommercebench.domain.money.Money;
import java.nio.file.Path;

/**
 * 单次基准运行的不可变配置。
 *
 * @param modelKey 模型注册表键，用于解析具体的 LLM Provider 与端点
 * @param maxTokens 单次 LLM 响应的最大输出 token 上限
 * @param maxTurns Agent 主循环允许的最大回合数
 * @param maxDays 仿真的最大经营天数
 * @param initialBalance 开局银行初始资金
 * @param dailyFee 每日固定运营费用
 * @param maxTokenCapacity 上下文窗口的 token 容量阈值，超过则触发旧消息清理
 * @param tokenizerPath HuggingFace tokenizer.json 路径，用于精确计数
 * @param dataDir 静态数据（CSV/JSON）目录
 * @param logDir 运行产物输出目录
 * @param jobFile 批量任务定义文件路径
 * @param runs 相同配置重复运行次数
 * @param seed 确定性随机根种子，同 seed 可完全复现
 */
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

  /** 紧凑构造器：拦截非正的运行上限与 runs，避免零/负预算进入仿真。 */
  public RunConfig {
    if (maxTokens <= 0 || maxTurns <= 0 || maxDays <= 0 || maxTokenCapacity <= 0 || runs <= 0) {
      throw new IllegalArgumentException("运行上限与 runs 必须为正数");
    }
  }

  /** 与 Python 参考实现对齐的一整套默认值（初始 10 万、日费 50、seed 42 等）。 */
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
