package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RunComparator 测试：按 METRIC_DEFS 的 JSON path 计算样本均值/标准差；null 被跳过而非视为 0；布尔按 1/0 计入。 */
class RunComparatorTest {

  private static void writeAnalysis(Path session, int index, String json) throws Exception {
    Path metrics = session.resolve("metrics");
    Files.createDirectories(metrics);
    Files.writeString(metrics.resolve("run_" + index + "_analysis.json"), json);
  }

  @Test
  void computesMeanStdAndSkipsNullMetrics(@TempDir Path tempDir) throws Exception {
    Path session = tempDir.resolve("20260101_080000_m");
    writeAnalysis(
        session,
        0,
        "{\"profitability\":{\"final_balance\":100.0,\"final_day\":3,\"bankrupt\":false},"
            + "\"negotiation_quality\":{\"SE+\":null}}");
    writeAnalysis(
        session,
        1,
        "{\"profitability\":{\"final_balance\":200.0,\"final_day\":5,\"bankrupt\":true},"
            + "\"negotiation_quality\":{\"SE+\":null}}");

    ComparisonReport report = new RunComparator().compare(List.of(session));

    assertThat(report.sessions()).hasSize(1);
    SessionComparison comparison = report.sessions().get(0);
    assertThat(comparison.name()).isEqualTo("20260101_080000_m");
    assertThat(comparison.runCount()).isEqualTo(2);

    MetricStat finalBalance = comparison.metric("final_balance");
    assertThat(finalBalance.mean()).isCloseTo(150.0, within(1e-9));
    assertThat(finalBalance.std()).isCloseTo(70.7106781, within(1e-5));
    assertThat(finalBalance.n()).isEqualTo(2);

    MetricStat sePlus = comparison.metric("SE+");
    assertThat(sePlus.mean()).isNull();
    assertThat(sePlus.n()).isZero();

    MetricStat bankrupt = comparison.metric("bankrupt");
    assertThat(bankrupt.mean()).isCloseTo(0.5, within(1e-9));
  }

  @Test
  void comparesMultipleSessions(@TempDir Path tempDir) throws Exception {
    Path sessionA = tempDir.resolve("20260101_080000_a");
    Path sessionB = tempDir.resolve("20260102_080000_b");
    writeAnalysis(sessionA, 0, "{\"profitability\":{\"final_balance\":100.0}}");
    writeAnalysis(sessionB, 0, "{\"profitability\":{\"final_balance\":300.0}}");

    ComparisonReport report = new RunComparator().compare(List.of(sessionA, sessionB));

    assertThat(report.sessions()).hasSize(2);
    assertThat(report.sessions().get(0).metric("final_balance").mean())
        .isCloseTo(100.0, within(1e-9));
    assertThat(report.sessions().get(1).metric("final_balance").mean())
        .isCloseTo(300.0, within(1e-9));
  }
}
