package io.github.ecommercebench.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.cli.BenchmarkCommand;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.llm.model.ToolCall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import picocli.CommandLine;

/**
 * 无真实 API 的三天端到端测试：用脚本化假 LLM 经完整 CLI 路径（BenchmarkCommand→RunCoordinator→RunComponentFactory→
 * 真实引擎/工具/tokenizer）驱动一次 episode，断言退出码 0、推进到第 3 天、开了店，并产出四类 Python 兼容文件（余额 CSV/消息 JSONL/两个 metrics
 * JSON）， 消息 JSONL 每行可解析，analysis 含 final_balance 与 negotiation_quality。全程离线。
 */
@SpringBootTest(
        classes = {EcommerceBenchApplication.class, BenchmarkEndToEndTest.ScriptedLlmConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BenchmarkEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RunComponentFactory factory;

    /**
     * 用脚本化假 LLM 覆盖默认 provider：主模型按轮次返回工具调用，NPC 返回固定文本。
     */
    @TestConfiguration
    static class ScriptedLlmConfig {
        @Bean
        @Primary
        LlmClientProvider scriptedProvider() {
            return config -> {
                if ("npc_tools".equals(config.key())) {
                    return request ->
                            new LlmResponse("Thanks for reaching out.", List.of(), null, List.of(), null);
                }
                return new ScriptedMainClient();
            };
        }
    }

    /**
     * 脚本：开店 → 连等三天（每次 wait 传当前引擎日期）；到第 3 天引擎终止（max_days）。
     */
    static final class ScriptedMainClient implements LlmClient {
        private int turn;

        @Override
        public LlmResponse generate(LlmRequest request) {
            turn++;
            return switch (turn) {
                case 1 -> call("c1", "open_store", "{\"store_type\":\"fashion\",\"store_name\":\"E2E Store\"}");
                case 2 -> call("c2", "wait_for_next_day", "{\"current_day\":\"2026-01-01\"}");
                case 3 -> call("c3", "wait_for_next_day", "{\"current_day\":\"2026-01-02\"}");
                case 4 -> call("c4", "wait_for_next_day", "{\"current_day\":\"2026-01-03\"}");
                default -> new LlmResponse("done", List.of(), null, List.of(), null);
            };
        }

        private static LlmResponse call(String id, String name, String argsJson) {
            try {
                JsonNode args = MAPPER.readTree(argsJson);
                return new LlmResponse(
                        "", List.of(new ToolCall(id, name, args, null)), null, List.of(), null);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    @Test
    void runsThreeDayEpisodeOfflineAndWritesCompatibleArtifacts(@TempDir Path tempDir)
            throws Exception {
        int exit =
                new CommandLine(new BenchmarkCommand(factory))
                        .execute(
                                "--model", "gpt-5.6-sol",
                                "--max-days", "3",
                                "--runs", "1",
                                "--seed", "42",
                                "--log-dir", tempDir.toString());

        assertThat(exit).isZero();

        Path session;
        try (Stream<Path> dirs = Files.list(tempDir)) {
            session = dirs.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        Path balance = session.resolve("balance/run_0_daily_balance.csv");
        Path messages = session.resolve("trajectories/run_0_messages.jsonl");
        Path analysis = session.resolve("metrics/run_0_analysis.json");
        Path negotiation = session.resolve("metrics/run_0_negotiation_metrics.json");

        assertThat(Files.size(balance)).isGreaterThan(0);
        assertThat(Files.size(messages)).isGreaterThan(0);
        assertThat(Files.exists(analysis)).isTrue();
        assertThat(Files.exists(negotiation)).isTrue();

        List<String> balanceLines = Files.readAllLines(balance);
        assertThat(balanceLines).hasSize(5); // 表头 + 第 0..3 天
        assertThat(balanceLines.get(0)).startsWith("date,bank_balance,platform_wallet,total_balance,");
        assertThat(balanceLines.get(4).split(",")[0]).isEqualTo("2026-01-04");

        for (String line : Files.readAllLines(messages)) {
            if (!line.isBlank()) {
                MAPPER.readTree(line); // 每行独立可解析
            }
        }

        JsonNode analysisRoot = MAPPER.readTree(Files.readString(analysis));
        assertThat(analysisRoot.get("profitability").get("final_balance").asDouble())
                .isGreaterThan(0.0);
        assertThat(analysisRoot.get("profitability").get("stores_opened").asInt()).isEqualTo(1);
        assertThat(analysisRoot.get("profitability").get("final_day").asInt()).isEqualTo(3);
        assertThat(analysisRoot.has("negotiation_quality")).isTrue();
    }
}
