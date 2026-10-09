package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.RunJob;
import io.github.ecommercebench.agent.RunResult;
import io.github.ecommercebench.agent.TerminationReason;
import io.github.ecommercebench.agent.tool.ToolExecutionResult;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.opponent.metrics.NegotiationMetrics;
import io.github.ecommercebench.simulation.SimulationEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 兼容产物写入测试：断言运行目录生成四类 Python 兼容文件——严格表头的余额 CSV、逐行独立 JSON 的消息 JSONL（含 tool_call_id /
 * reasoning_content / context_truncation 事件）、两个 metrics JSON（undefined 指标为 JSON null），且不泄漏密钥。
 */
class CompatibleRunLoggingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DATA =
            Path.of(System.getProperty("user.dir"), "..", "data").normalize();
    private static final String SECRET = "sk-ecbench-SECRET-do-not-log-000000";

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

    private static CompositeRunObserver observer(Path tempDir, SimulationEngine engine) {
        RunDirectory dir = RunDirectory.create(tempDir, "20260101_080000", "fake/model");
        return new CompositeRunObserver(
                dir, 0, engine, MAPPER, CompatibleRunLoggingTest::emptyNegotiation);
    }

    @Test
    void writesStrictBalanceCsvHeaderAndOneRowPerDay(@TempDir Path tempDir) throws Exception {
        SimulationEngine engine = newEngine();
        try (CompositeRunObserver observer = observer(tempDir, engine)) {
            observer.onRunStart(seedJob());

            engine.advanceToNextDay(engine.currentDate());
            observer.onToolResults(List.of(new ToolExecutionResult("c1", "wait_for_next_day", "{}")));

            observer.onRunComplete(
                    new RunResult(
                            TerminationReason.ENV_COMPLETED,
                            "max_days",
                            2,
                            List.of(),
                            engine.state().dayCount(),
                            engine.currentDate().toString(),
                            engine.state().totalAssets().amount().doubleValue(),
                            0,
                            0));
        }

        Path csv = RunDirectory.create(tempDir, "20260101_080000", "fake/model").balanceCsv(0);
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines.get(0))
                .isEqualTo(
                        "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,"
                                + "storage_charged");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1).split(",")[0]).isEqualTo("2026-01-01");
        assertThat(lines.get(2).split(",")[0]).isEqualTo("2026-01-02");
        String[] day0 = lines.get(1).split(",");
        assertThat(day0[1]).isEqualTo("100000.00");
        assertThat(day0[3]).isEqualTo("100000.00");
    }

    @Test
    void writesJsonlMessagesWithToolCallIdReasoningAndTruncation(@TempDir Path tempDir)
            throws Exception {
        SimulationEngine engine = newEngine();
        ObjectNode args = MAPPER.createObjectNode().put("uids", "a@x.com");
        try (CompositeRunObserver observer = observer(tempDir, engine)) {
            observer.onRunStart(seedJob());
            observer.onAssistantMessage(
                    ChatMessage.assistant(
                            "Let me negotiate.",
                            List.of(new ToolCall("call_1", "chatbox", args, null)),
                            "reasoning trace",
                            List.of()));
            observer.onToolResults(
                    List.of(new ToolExecutionResult("call_1", "chatbox", "{\"message\":\"sent\"}")));
            observer.onContextTruncation(3, 500);
        }

        Path jsonl = RunDirectory.create(tempDir, "20260101_080000", "fake/model").messagesJsonl(0);
        List<String> lines = Files.readAllLines(jsonl);
        assertThat(lines).isNotEmpty();
        boolean sawToolCallId = false;
        boolean sawReasoning = false;
        boolean sawTruncation = false;
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);
            if (node.has("tool_call_id")) {
                sawToolCallId = true;
                assertThat(node.get("tool_call_id").asText()).isEqualTo("call_1");
                assertThat(node.get("role").asText()).isEqualTo("tool");
            }
            if (node.has("reasoning_content")) {
                sawReasoning = true;
                assertThat(node.get("reasoning_content").asText()).isEqualTo("reasoning trace");
                assertThat(node.get("tool_calls").get(0).get("function").get("name").asText())
                        .isEqualTo("chatbox");
                assertThat(node.get("tool_calls").get(0).get("id").asText()).isEqualTo("call_1");
            }
            if (node.path("_event").asText("").equals("context_truncation")) {
                sawTruncation = true;
                assertThat(node.get("turn").asInt()).isEqualTo(3);
                assertThat(node.get("tokens_freed").asInt()).isEqualTo(500);
            }
        }
        assertThat(sawToolCallId).isTrue();
        assertThat(sawReasoning).isTrue();
        assertThat(sawTruncation).isTrue();
    }

    @Test
    void writesBothMetricsJsonWithNullForUndefinedMetrics(@TempDir Path tempDir) throws Exception {
        SimulationEngine engine = newEngine();
        try (CompositeRunObserver observer = observer(tempDir, engine)) {
            observer.onRunStart(seedJob());
            observer.onRunComplete(
                    new RunResult(
                            TerminationReason.ENV_COMPLETED,
                            "max_days",
                            1,
                            List.of(),
                            1,
                            "2026-01-02",
                            100000.0,
                            0,
                            0));
        }

        RunDirectory dir = RunDirectory.create(tempDir, "20260101_080000", "fake/model");
        Path negotiation = dir.negotiationMetricsJson(0);
        Path analysis = dir.analysisJson(0);
        assertThat(Files.exists(negotiation)).isTrue();
        assertThat(Files.exists(analysis)).isTrue();

        JsonNode negRoot = MAPPER.readTree(Files.readString(negotiation));
        assertThat(negRoot.get("total_negotiations").asInt()).isZero();
        assertThat(negRoot.get("terms_bench_metrics").get("SE+").isNull()).isTrue();

        JsonNode analysisRoot = MAPPER.readTree(Files.readString(analysis));
        assertThat(analysisRoot.get("reward").get("termination_reason").asText())
                .isEqualTo("env_completed");
        assertThat(analysisRoot.get("profitability").get("final_balance").asDouble())
                .isEqualTo(100000.0);
        assertThat(analysisRoot.get("profitability").get("initial_balance").asDouble())
                .isEqualTo(100000.0);
        // 无谈判时 TERMS 指标为 JSON null（undefined）
        assertThat(analysisRoot.get("negotiation_quality").get("SE+").isNull()).isTrue();
        assertThat(analysisRoot.get("negotiation_quality").get("avg_rounds_to_deal").asDouble())
                .isEqualTo(0.0);
        assertThat(analysisRoot.get("profitability").get("peak_drawdown").asDouble()).isEqualTo(0.0);
        assertThat(analysisRoot.get("profitability").get("peak_total_assets").asDouble())
                .isEqualTo(100000.0);
        assertThat(analysisRoot.get("profitability").get("store_reopens").asInt()).isZero();
        assertThat(analysisRoot.get("fulfilment_quality").get("orders_sold").asInt()).isZero();
        assertThat(analysisRoot.get("fulfilment_quality").get("ship_speed_counts").get("fast").asInt())
                .isZero();
        assertThat(analysisRoot.get("return_management").get("units_shipped").asInt()).isZero();
        assertThat(analysisRoot.get("return_management").get("exp_return_rate").asDouble())
                .isEqualTo(0.0);
        assertThat(analysisRoot.get("fraud_identification").get("orders_total").asInt()).isZero();
        assertThat(analysisRoot.get("fraud_identification").get("bad_suppliers_total").asInt())
                .isGreaterThan(0);
        JsonNode engagement = analysisRoot.get("supplier_engagement");
        assertThat(engagement.get("contacted").get("distinct_total").asInt()).isZero();
        assertThat(engagement.get("ordered").get("distinct_total").asInt()).isZero();
        assertThat(engagement.get("roster_totals").get("distinct_bad").asInt()).isGreaterThan(0);
        assertThat(engagement.get("roster_totals").get("distinct_good").asInt()).isGreaterThan(0);
    }

    @Test
    void neverWritesSecretKeyMaterial(@TempDir Path tempDir) throws Exception {
        SimulationEngine engine = newEngine();
        try (CompositeRunObserver observer = observer(tempDir, engine)) {
            observer.onRunStart(seedJob());
            observer.onAssistantMessage(ChatMessage.assistant(SECRET, List.of(), null, List.of()));
            observer.onToolResults(List.of(new ToolExecutionResult("c", "check_balance", "{}")));
        }

        RunDirectory dir = RunDirectory.create(tempDir, "20260101_080000", "fake/model");
        // assistant 正文允许包含用户文本；密钥不得出现在余额/指标等非消息产物中。
        assertThat(Files.readString(dir.balanceCsv(0))).doesNotContain(SECRET);
        assertThat(Files.readString(dir.outputLog(0))).doesNotContain(SECRET);
    }
}
