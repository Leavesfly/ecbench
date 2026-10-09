package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 运营效率面板测试（论文 §E.4）：断言 {@code operational_efficiency} 输出每次工具调用利润、工具调用/轮次、八类活动带计数、上下文驱逐与记忆使用； 调用数为 0
 * 时比率型指标为 JSON null。
 */
class MetricsJsonWriterOperationalEfficiencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DATA =
            Path.of(System.getProperty("user.dir"), "..", "data").normalize();

    private static SimulationEngine newEngine() {
        CatalogData catalog = new CsvCatalogLoader().load(DATA);
        return new SimulationEngine(catalog, RunConfig.defaults(), new RandomStreams(42L));
    }

    private static NegotiationMetrics emptyNegotiation() {
        return new NegotiationMetrics(
                0, null, null, null, null, null, null, 0.0, 0.0, Map.of(), Map.of());
    }

    private static RunResult result(double finalBalance, int turns, int evictions, int tokensFreed) {
        return new RunResult(
                TerminationReason.ENV_COMPLETED,
                "max_days",
                turns,
                List.of(),
                365,
                "2026-12-31",
                finalBalance,
                evictions,
                tokensFreed);
    }

    private JsonNode writeAndRead(
            Path tempDir, SimulationEngine engine, RunResult result, Map<String, Integer> toolCallCounts)
            throws Exception {
        RunDirectory directory = RunDirectory.create(tempDir, "20260101_080000", "fake");
        try (MetricsJsonWriter writer = new MetricsJsonWriter(directory, 0, MAPPER)) {
            writer.writeAnalysis(result, engine, emptyNegotiation(), 0.0, 100000.0, toolCallCounts);
        }
        return MAPPER
                .readTree(Files.readString(directory.analysisJson(0)))
                .get("operational_efficiency");
    }

    @Test
    void computesProfitPerCallBandsEvictionsAndMemory(@TempDir Path tempDir) throws Exception {
        SimulationEngine engine = newEngine();
        // 初始资本 ¥100,000；年末 ¥150,000 → 利润 ¥50,000；10 次调用 → 每次 ¥5,000
        Map<String, Integer> counts =
                Map.of("chatbox", 4, "check_balance", 2, "operate_memory", 2, "set_prices", 2);

        JsonNode panel = writeAndRead(tempDir, engine, result(150000.0, 7, 2, 1500), counts);

        assertThat(panel.get("total_tool_calls").asInt()).isEqualTo(10);
        assertThat(panel.get("turns").asInt()).isEqualTo(7);
        assertThat(panel.get("profit_per_tool_call").asDouble()).isCloseTo(5000.0, within(1e-9));
        assertThat(panel.get("context_evictions").asInt()).isEqualTo(2);
        assertThat(panel.get("context_tokens_freed").asInt()).isEqualTo(1500);
        assertThat(panel.get("memory_calls").asInt()).isEqualTo(2);
        assertThat(panel.get("memory_share").asDouble()).isCloseTo(0.2, within(1e-9));

        JsonNode bands = panel.get("calls_by_band");
        assertThat(bands.get("bargaining").asInt()).isEqualTo(4);
        assertThat(bands.get("state_polling").asInt()).isEqualTo(2);
        assertThat(bands.get("memory").asInt()).isEqualTo(2);
        assertThat(bands.get("remainder").asInt()).isEqualTo(2);
        assertThat(bands.get("waiting").asInt()).isZero();
        assertThat(bands.get("shipping").asInt()).isZero();
        assertThat(bands.get("listing").asInt()).isZero();
        assertThat(bands.get("withdraw").asInt()).isZero();

        // 活动带占比 %（论文 Table 10）：总数 10 → 各带占比
        JsonNode shares = panel.get("calls_by_band_share");
        assertThat(shares.get("bargaining").asDouble()).isCloseTo(40.0, within(1e-9));
        assertThat(shares.get("state_polling").asDouble()).isCloseTo(20.0, within(1e-9));
        assertThat(shares.get("memory").asDouble()).isCloseTo(20.0, within(1e-9));
        assertThat(shares.get("remainder").asDouble()).isCloseTo(20.0, within(1e-9));
        assertThat(shares.get("waiting").asDouble()).isZero();
        assertThat(shares.get("withdraw").asDouble()).isZero();
    }

    @Test
    void ratioMetricsAreNullWhenNoToolCallsIssued(@TempDir Path tempDir) throws Exception {
        SimulationEngine engine = newEngine();

        JsonNode panel = writeAndRead(tempDir, engine, result(100000.0, 0, 0, 0), Map.of());

        assertThat(panel.get("total_tool_calls").asInt()).isZero();
        assertThat(panel.get("profit_per_tool_call").isNull()).isTrue();
        assertThat(panel.get("memory_share").isNull()).isTrue();
        assertThat(panel.get("memory_calls").asInt()).isZero();
        assertThat(panel.get("calls_by_band").get("bargaining").asInt()).isZero();
        // 无调用时各带占比退化为 0.0（保持数值型、形状稳定）
        assertThat(panel.get("calls_by_band_share").get("bargaining").asDouble()).isZero();
    }
}
