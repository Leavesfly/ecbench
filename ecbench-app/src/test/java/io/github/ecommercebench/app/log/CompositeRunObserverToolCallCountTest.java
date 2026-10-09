package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.RunJob;
import io.github.ecommercebench.agent.RunResult;
import io.github.ecommercebench.agent.TerminationReason;
import io.github.ecommercebench.agent.tool.ToolExecutionResult;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.opponent.metrics.NegotiationMetrics;
import io.github.ecommercebench.simulation.SimulationEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 观察者工具调用计数测试：断言 {@link CompositeRunObserver} 从每批 {@code onToolResults} 逐工具累加调用次数，并在 {@code
 * onRunComplete} 时写入 {@code operational_efficiency} 面板（总调用数与八类活动带计数）。
 */
class CompositeRunObserverToolCallCountTest {

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

    private static RunJob seedJob() {
        return new RunJob(
                "agent_multiturn/long_horizon/ecommerce_bench",
                0,
                "ecommerce_bench",
                List.of(ChatMessage.system("You are an assistant."), ChatMessage.user("Start.")),
                List.of(),
                4000,
                365,
                128_000,
                64 * 1024);
    }

    @Test
    void accumulatesToolCallsAcrossBatchesIntoOperationalEfficiency(@TempDir Path tempDir)
            throws Exception {
        SimulationEngine engine = newEngine();
        RunDirectory dir = RunDirectory.create(tempDir, "20260101_080000", "fake/model");
        try (CompositeRunObserver observer =
                     new CompositeRunObserver(
                             dir, 0, engine, MAPPER, CompositeRunObserverToolCallCountTest::emptyNegotiation)) {
            observer.onRunStart(seedJob());
            observer.onToolResults(
                    List.of(
                            new ToolExecutionResult("c1", "chatbox", "{}"),
                            new ToolExecutionResult("c2", "chatbox", "{}"),
                            new ToolExecutionResult("c3", "check_balance", "{}")));
            observer.onToolResults(List.of(new ToolExecutionResult("c4", "operate_memory", "{}")));
            observer.onRunComplete(
                    new RunResult(
                            TerminationReason.ENV_COMPLETED,
                            "max_days",
                            2,
                            List.of(),
                            1,
                            "2026-01-02",
                            100000.0,
                            0,
                            0));
        }

        JsonNode panel =
                MAPPER.readTree(Files.readString(dir.analysisJson(0))).get("operational_efficiency");
        assertThat(panel.get("total_tool_calls").asInt()).isEqualTo(4);
        assertThat(panel.get("calls_by_band").get("bargaining").asInt()).isEqualTo(2);
        assertThat(panel.get("calls_by_band").get("state_polling").asInt()).isEqualTo(1);
        assertThat(panel.get("calls_by_band").get("memory").asInt()).isEqualTo(1);
        assertThat(panel.get("memory_calls").asInt()).isEqualTo(1);
    }
}
