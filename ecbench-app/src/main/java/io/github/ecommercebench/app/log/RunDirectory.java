package io.github.ecommercebench.app.log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一次基准会话的运行目录，端口 run.sh 的 BASE_LOG_DIR/TIMESTAMP_SAFEMODEL 布局。
 *
 * <p>{@link #create} 在给定基目录下创建 {@code {timestamp}_{model}} 会话目录及其三个子目录
 * trajectories/metrics/balance， 并按 {@code run_{idx}_*} 命名规则暴露各产物文件路径。模型名中的非 {@code [a-zA-Z0-9._-]}
 * 字符替换为下划线，与 run.sh 的 sed 一致。
 */
public final class RunDirectory {

  private final Path sessionDir;

  private RunDirectory(Path sessionDir) {
    this.sessionDir = sessionDir;
  }

  public static RunDirectory create(Path logDir, String timestamp, String model) {
    Path session = logDir.resolve(timestamp + "_" + sanitize(model));
    try {
      Files.createDirectories(session.resolve("trajectories"));
      Files.createDirectories(session.resolve("metrics"));
      Files.createDirectories(session.resolve("balance"));
    } catch (IOException exception) {
      throw new UncheckedIOException("无法创建运行目录: " + session, exception);
    }
    return new RunDirectory(session);
  }

  public Path sessionDir() {
    return sessionDir;
  }

  public Path trajectoriesDir() {
    return sessionDir.resolve("trajectories");
  }

  public Path metricsDir() {
    return sessionDir.resolve("metrics");
  }

  public Path balanceDir() {
    return sessionDir.resolve("balance");
  }

  public Path messagesJsonl(int runIndex) {
    return trajectoriesDir().resolve("run_" + runIndex + "_messages.jsonl");
  }

  public Path outputLog(int runIndex) {
    return trajectoriesDir().resolve("run_" + runIndex + "_output.log");
  }

  public Path balanceCsv(int runIndex) {
    return balanceDir().resolve("run_" + runIndex + "_daily_balance.csv");
  }

  public Path negotiationMetricsJson(int runIndex) {
    return metricsDir().resolve("run_" + runIndex + "_negotiation_metrics.json");
  }

  public Path analysisJson(int runIndex) {
    return metricsDir().resolve("run_" + runIndex + "_analysis.json");
  }

  private static String sanitize(String model) {
    return model == null ? "unknown" : model.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
