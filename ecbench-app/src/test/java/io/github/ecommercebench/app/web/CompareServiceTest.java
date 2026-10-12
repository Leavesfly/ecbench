package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareServiceTest {

    private final CompareService service = new CompareService(new ObjectMapper());

    private void session(Path dir, double finalBalance) throws Exception {
        Files.createDirectories(dir.resolve("metrics"));
        Files.writeString(
                dir.resolve("metrics/run_0_analysis.json"),
                "{\"profitability\":{\"final_balance\":" + finalBalance
                        + ",\"initial_balance\":100000.0,\"peak_total_assets\":100000.0,"
                        + "\"peak_drawdown\":0.0,\"stores_opened\":1,\"store_reopens\":0},"
                        + "\"operational_efficiency\":{\"total_tool_calls\":10}}");
    }

    @Test
    void buildsSessionsRadarAndRanking(@TempDir Path log) throws Exception {
        Path a = log.resolve("2026_a-model");
        Path b = log.resolve("2026_b-model");
        session(a, 90000);
        session(b, 120000);

        JsonNode node = service.compare(List.of(a, b));

        assertThat(node.path("sessions")).hasSize(2);
        assertThat(node.path("radar").path("axes")).hasSize(7);
        assertThat(node.path("radar").path("series")).hasSize(2);
        assertThat(node.path("radar").path("series").get(0).path("values")).hasSize(7);
        assertThat(node.path("ranking").get(0).path("name").asText()).isEqualTo("2026_b-model");
        assertThat(node.path("ranking").get(0).path("rank").asInt()).isEqualTo(1);
    }

    @Test
    void metricCellCarriesMeanAndFallback(@TempDir Path log) throws Exception {
        Path a = log.resolve("2026_a-model");
        session(a, 90000);

        JsonNode metrics = service.compare(List.of(a)).path("sessions").get(0).path("metrics");

        assertThat(metrics).isNotEmpty();
        JsonNode finalBalance = find(metrics, "final_balance");
        assertThat(finalBalance.path("mean").asDouble()).isEqualTo(90000.0);
        JsonNode stores = find(metrics, "stores / reopens");
        assertThat(stores.path("mean").isNull()).isTrue();
        assertThat(stores.path("fallback").asText()).isEqualTo("1/0");
    }

    @Test
    void missingAxisValueIsSerialisedAsNull(@TempDir Path log) throws Exception {
        Path a = log.resolve("2026_a-model");
        session(a, 90000);
        Path b = log.resolve("2026_b-model");
        session(b, 120000);

        JsonNode node = service.compare(List.of(a, b));

        // 谈判质量轴（CSE+）在 fixture 中缺失：归一化值应为 JSON null 而非 NaN。
        for (JsonNode series : node.path("radar").path("series")) {
            assertThat(series.path("values").get(1).isNull()).isTrue();
        }
    }

    @Test
    void emptyInputYieldsEmptyShape() {
        JsonNode node = service.compare(List.of());

        assertThat(node.path("sessions")).isEmpty();
        assertThat(node.path("radar").path("series")).isEmpty();
        assertThat(node.path("ranking")).isEmpty();
    }

    @Test
    void appendsPaperDimensionSectionsWithoutTouchingLegacyOnes(@TempDir Path log) throws Exception {
        Path a = log.resolve("2026_a-model");
        Path b = log.resolve("2026_b-model");
        session(a, 90000);
        session(b, 120000);

        JsonNode node = service.compare(List.of(a, b));

        // 既有三段不变
        assertThat(node.path("sessions")).hasSize(2);
        assertThat(node.path("radar").path("axes")).hasSize(7);
        assertThat(node.path("ranking").get(0).path("name").asText()).isEqualTo("2026_b-model");
        // 新增四段
        assertThat(node.path("primaryScore").path("labelZh").asText()).isEqualTo("年末总资产倍数");
        assertThat(node.path("primaryScore").path("rows")).hasSize(2);
        assertThat(node.path("dimensions")).hasSize(6);
        assertThat(node.path("rankCrossings")).hasSize(2);
        assertThat(node.path("learning").path("2026_a-model")).isNotNull();
        assertThat(node.path("learning").path("2026_a-model").path("anchorRatio").isNull()).isTrue();
    }

    @Test
    void dimensionsSurviveEmptyInput(@TempDir Path log) {
        JsonNode node = service.compare(List.of());

        assertThat(node.path("dimensions")).hasSize(6);
        assertThat(node.path("dimensions").get(0).path("key").asText()).isEqualTo("negotiation");
        assertThat(node.path("primaryScore").path("rows")).isEmpty();
        assertThat(node.path("rankCrossings")).isEmpty();
    }

    private static JsonNode find(JsonNode metrics, String label) {
        for (JsonNode metric : metrics) {
            if (label.equals(metric.path("label").asText())) {
                return metric;
            }
        }
        throw new AssertionError("未找到指标: " + label);
    }
}
