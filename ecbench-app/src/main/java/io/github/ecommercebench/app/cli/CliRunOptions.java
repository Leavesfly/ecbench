package io.github.ecommercebench.app.cli;

import picocli.CommandLine.Option;

/**
 * 与 Python {@code run.py} 兼容的命令行参数集合。
 *
 * <p>参数名、默认值与类型逐一对齐 Python argparse：{@code --model}（必填）、{@code --max-tokens}、 {@code
 * --max-turns}、{@code --max-days}、{@code --initial-balance}、{@code --daily-fee}、{@code
 * --max-token-capacity}、 {@code --tokenizer-path}、{@code --log-dir}、{@code --job-file}、{@code
 * --runs}；另新增两个可选参数 {@code --seed}（确定性随机种子）与 {@code --data-dir}（数据目录覆盖）， 均不破坏原 CLI 兼容性。
 */
public final class CliRunOptions {

  @Option(names = "--model", description = "models_config.json 中的模型键（运行基准时必填）")
  private String model;

  @Option(names = "--max-tokens", defaultValue = "16384", description = "单次 LLM 调用的最大 token 数")
  private int maxTokens;

  @Option(names = "--max-turns", defaultValue = "4000", description = "单次 episode 的最大 agent 轮数")
  private int maxTurns;

  @Option(names = "--max-days", defaultValue = "365", description = "最大模拟天数")
  private int maxDays;

  @Option(names = "--initial-balance", defaultValue = "100000.0", description = "初始银行余额")
  private double initialBalance;

  @Option(names = "--daily-fee", defaultValue = "50.0", description = "每日店铺运营费")
  private double dailyFee;

  @Option(names = "--max-token-capacity", defaultValue = "128000", description = "上下文窗口 token 容量")
  private int maxTokenCapacity;

  @Option(
      names = "--tokenizer-path",
      description = "HuggingFace tokenizer 路径（默认：java-impl/tokenizer/tokenizer.json）")
  private String tokenizerPath;

  @Option(names = "--log-dir", description = "日志输出目录")
  private String logDir;

  @Option(names = "--job-file", description = "预构建的 job JSONL 文件")
  private String jobFile;

  @Option(names = "--runs", defaultValue = "1", description = "并行运行次数")
  private int runs;

  @Option(names = "--seed", description = "确定性随机种子（默认：42）")
  private Long seed;

  @Option(names = "--data-dir", description = "数据目录覆盖（默认：java-impl 隔离副本 data/）")
  private String dataDir;

  public String model() {
    return model;
  }

  public int maxTokens() {
    return maxTokens;
  }

  public int maxTurns() {
    return maxTurns;
  }

  public int maxDays() {
    return maxDays;
  }

  public double initialBalance() {
    return initialBalance;
  }

  public double dailyFee() {
    return dailyFee;
  }

  public int maxTokenCapacity() {
    return maxTokenCapacity;
  }

  public String tokenizerPath() {
    return tokenizerPath;
  }

  public String logDir() {
    return logDir;
  }

  public String jobFile() {
    return jobFile;
  }

  public int runs() {
    return runs;
  }

  public Long seed() {
    return seed;
  }

  public String dataDir() {
    return dataDir;
  }
}
