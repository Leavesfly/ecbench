package io.github.ecommercebench.evaluation.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.evaluation.model.BalancePoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 兼容读取器测试：识别 organized/legacy 两种布局；余额读取以 total_balance 为权威列（不等于 bank+wallet 时仍取原值）；消息统计 assistant
 * 轮数/工具调用/上下文裁剪；坏行错误含文件与行号。
 */
class CompatibleReadersTest {

  private static final String BALANCE_CSV =
      """
      date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,storage_charged
      2026-01-01,1000.00,200.00,1500.00,1,10,5.00
      2026-01-02,900.00,300.00,1600.00,1,8,6.00
      """;

  private static final String MESSAGES_JSONL =
      """
      {"role":"system","content":"sys"}
      {"role":"user","content":"go"}
      {"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"chatbox"}}]}
      {"role":"tool","content":"{}","tool_call_id":"c1"}
      {"_event":"context_truncation","turn":2,"tokens_freed":100}
      {"role":"assistant","content":"done"}
      """;

  private static final String ANALYSIS_JSON =
      """
      {"reward":{"final_score":1.05},"profitability":{"final_balance":105000.0,"final_day":3}}
      """;

  private Path writeOrganized(Path root) throws Exception {
    Path session = root.resolve("20260101_080000_fake");
    Files.createDirectories(session.resolve("balance"));
    Files.createDirectories(session.resolve("trajectories"));
    Files.createDirectories(session.resolve("metrics"));
    Files.writeString(session.resolve("balance/run_0_daily_balance.csv"), BALANCE_CSV);
    Files.writeString(session.resolve("trajectories/run_0_messages.jsonl"), MESSAGES_JSONL);
    Files.writeString(session.resolve("metrics/run_0_analysis.json"), ANALYSIS_JSON);
    return session;
  }

  private Path writeLegacy(Path root) throws Exception {
    Path session = root.resolve("20260102_090000_legacy");
    Files.createDirectories(session);
    Files.writeString(session.resolve("run_0_daily_balance.csv"), BALANCE_CSV);
    Files.writeString(session.resolve("run_0_messages.jsonl"), MESSAGES_JSONL);
    Files.writeString(session.resolve("run_0_analysis.json"), ANALYSIS_JSON);
    return session;
  }

  @Test
  void resolvesOrganizedLayout(@TempDir Path tempDir) throws Exception {
    Path session = writeOrganized(tempDir);

    RunLayoutResolver.RunLayout layout = new RunLayoutResolver().resolve(session);

    assertThat(layout.organized()).isTrue();
    assertThat(layout.balanceCsv(0)).isEqualTo(session.resolve("balance/run_0_daily_balance.csv"));
    assertThat(layout.messagesJsonl(0))
        .isEqualTo(session.resolve("trajectories/run_0_messages.jsonl"));
    assertThat(layout.analysisJson(0)).isEqualTo(session.resolve("metrics/run_0_analysis.json"));
    assertThat(Files.exists(layout.balanceCsv(0))).isTrue();
  }

  @Test
  void resolvesLegacyFlatLayout(@TempDir Path tempDir) throws Exception {
    Path session = writeLegacy(tempDir);

    RunLayoutResolver.RunLayout layout = new RunLayoutResolver().resolve(session);

    assertThat(layout.organized()).isFalse();
    assertThat(layout.balanceCsv(0)).isEqualTo(session.resolve("run_0_daily_balance.csv"));
    assertThat(layout.messagesJsonl(0)).isEqualTo(session.resolve("run_0_messages.jsonl"));
    assertThat(Files.exists(layout.balanceCsv(0))).isTrue();
  }

  @Test
  void balanceReaderPrefersAuthoritativeTotalColumn(@TempDir Path tempDir) throws Exception {
    Path session = writeOrganized(tempDir);
    Path csv = new RunLayoutResolver().resolve(session).balanceCsv(0);

    List<BalancePoint> points = new BalanceCsvReader().read(csv);

    assertThat(points).hasSize(2);
    assertThat(points.get(0).date()).isEqualTo("2026-01-01");
    assertThat(points.get(0).bankBalance()).isEqualTo(1000.00);
    assertThat(points.get(0).platformWallet()).isEqualTo(200.00);
    // total_balance (1500) != bank+wallet (1200): 权威列原值胜出
    assertThat(points.get(0).totalBalance()).isEqualTo(1500.00);
    assertThat(points.get(1).totalBalance()).isEqualTo(1600.00);
  }

  @Test
  void messageReaderCountsTurnsToolCallsAndTruncations(@TempDir Path tempDir) throws Exception {
    Path session = writeOrganized(tempDir);
    Path jsonl = new RunLayoutResolver().resolve(session).messagesJsonl(0);

    MessageJsonlReader.Result result = new MessageJsonlReader().read(jsonl);

    assertThat(result.assistantTurns()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(1);
    assertThat(result.contextTruncations()).isEqualTo(1);
    assertThat(result.messages()).hasSize(6);
  }

  @Test
  void analysisReaderParsesTree(@TempDir Path tempDir) throws Exception {
    Path session = writeOrganized(tempDir);
    Path json = new RunLayoutResolver().resolve(session).analysisJson(0);

    assertThat(new AnalysisJsonReader().read(json).get("profitability").get("final_day").asInt())
        .isEqualTo(3);
  }

  @Test
  void reportsFileAndLineNumberOnBadMessageLine(@TempDir Path tempDir) throws Exception {
    Path bad = tempDir.resolve("bad_messages.jsonl");
    Files.writeString(bad, "{\"role\":\"user\"}\n{ this is not json\n");

    assertThatThrownBy(() -> new MessageJsonlReader().read(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bad_messages.jsonl")
        .hasMessageContaining("2");
  }
}
