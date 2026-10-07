package io.github.ecommercebench.evaluation.io;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行目录布局探测器，端口 Python {@code plot_daily_balance._select_runs_from_log_dir} 的 organized/legacy 兼容逻辑。
 *
 * <p>organized 布局把产物分置于 balance/、trajectories/、metrics/ 子目录；legacy 布局把它们平铺在会话目录根。探测按子目录独立回退：
 * 某类子目录存在则用之，否则回退到会话目录根，从而两种布局都能定位 {@code run_{idx}_*} 产物。
 */
public final class RunLayoutResolver {

  /** 一次会话目录的已解析布局；各产物路径按 run index 生成。 */
  public record RunLayout(
      Path sessionDir, Path balanceDir, Path trajectoriesDir, Path metricsDir, boolean organized) {

    public Path balanceCsv(int runIndex) {
      return balanceDir.resolve("run_" + runIndex + "_daily_balance.csv");
    }

    public Path messagesJsonl(int runIndex) {
      return trajectoriesDir.resolve("run_" + runIndex + "_messages.jsonl");
    }

    public Path analysisJson(int runIndex) {
      return metricsDir.resolve("run_" + runIndex + "_analysis.json");
    }

    public Path negotiationMetricsJson(int runIndex) {
      return metricsDir.resolve("run_" + runIndex + "_negotiation_metrics.json");
    }
  }

  public RunLayout resolve(Path sessionDir) {
    Path balance = subdirOrRoot(sessionDir, "balance");
    Path trajectories = subdirOrRoot(sessionDir, "trajectories");
    Path metrics = subdirOrRoot(sessionDir, "metrics");
    boolean organized =
        !balance.equals(sessionDir)
            || !trajectories.equals(sessionDir)
            || !metrics.equals(sessionDir);
    return new RunLayout(sessionDir, balance, trajectories, metrics, organized);
  }

  private static Path subdirOrRoot(Path sessionDir, String name) {
    Path subdir = sessionDir.resolve(name);
    return Files.isDirectory(subdir) ? subdir : sessionDir;
  }
}
