package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.RunResult;
import io.github.ecommercebench.agent.TerminationReason;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.metrics.NegotiationMetrics;
import io.github.ecommercebench.simulation.SimulationEngine;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 6 golden fixture 结构对比：Java 产出的 analysis.json 的键路径集合覆盖 Python {@code get_analysis_report()}
 * 的全部键路径。
 *
 * <p>golden 由真实 Python 运行时导出（{@code golden/python-analysis-structure.json}）。因 Java 采用自有确定性、不与
 * Python 逐帧数值一致， 这里对比的是**结构**（全部 JSON 键路径）而非运行期数值；仅对 catalog
 * 派生的确定性字段（roster_totals、bad_suppliers_total）做数值等值断言。 Java 另含 {@code reward} 面板（对应 Python {@code
 * _save_analysis_report} 在 get_analysis_report 之上追加的 reward）。
 */
class AnalysisGoldenParityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path DATA =
      Path.of(System.getProperty("user.dir"), "..", "data").normalize();

  private static void collectPaths(JsonNode node, String prefix, Set<String> out) {
    node.fieldNames()
        .forEachRemaining(
            name -> {
              String path = prefix.isEmpty() ? name : prefix + "/" + name;
              out.add(path);
              collectPaths(node.get(name), path, out);
            });
  }

  private static Set<String> pathsOf(JsonNode node) {
    Set<String> paths = new HashSet<>();
    collectPaths(node, "", paths);
    return paths;
  }

  @Test
  void javaAnalysisCoversAllPythonGoldenKeyPaths(@TempDir Path tempDir) throws Exception {
    JsonNode golden;
    try (InputStream in =
        getClass().getResourceAsStream("/golden/python-analysis-structure.json")) {
      assertThat(in).isNotNull();
      golden = MAPPER.readTree(in);
    }

    CatalogData catalog = new CsvCatalogLoader().load(DATA);
    SimulationEngine engine =
        new SimulationEngine(catalog, RunConfig.defaults(), new RandomStreams(42L));
    RunDirectory directory = RunDirectory.create(tempDir, "20260101_080000", "fake");
    NegotiationMetrics emptyNegotiation =
        new NegotiationMetrics(0, null, null, null, null, null, null, 0.0, 0.0, Map.of(), Map.of());
    try (MetricsJsonWriter writer = new MetricsJsonWriter(directory, 0, MAPPER)) {
      writer.writeAnalysis(
          new RunResult(
              TerminationReason.ENV_COMPLETED,
              "max_days",
              1,
              List.of(),
              1,
              "2026-01-02",
              100000.0,
              0,
              0),
          engine,
          emptyNegotiation,
          0.0);
    }
    JsonNode java = MAPPER.readTree(Files.readString(directory.analysisJson(0)));

    Set<String> goldenPaths = pathsOf(golden);
    Set<String> javaPaths = pathsOf(java);

    // Java 覆盖 Python get_analysis_report 的全部键路径
    assertThat(javaPaths).containsAll(goldenPaths);
    // Java 另有 reward 面板（对应 _save_analysis_report 的追加）
    assertThat(javaPaths).contains("reward", "reward/final_score", "reward/termination_reason");

    // catalog 派生的确定性字段应与 Python 数值一致
    assertThat(java.get("fraud_identification").get("bad_suppliers_total").asInt())
        .isEqualTo(golden.get("fraud_identification").get("bad_suppliers_total").asInt());
    assertThat(java.get("supplier_engagement").get("roster_totals").get("distinct_bad").asInt())
        .isEqualTo(
            golden.get("supplier_engagement").get("roster_totals").get("distinct_bad").asInt());
    assertThat(java.get("supplier_engagement").get("roster_totals").get("distinct_good").asInt())
        .isEqualTo(
            golden.get("supplier_engagement").get("roster_totals").get("distinct_good").asInt());
  }
}
