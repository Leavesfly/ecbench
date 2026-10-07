package io.github.ecommercebench.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.tool.ToolExecutionResult;
import io.github.ecommercebench.app.log.BalanceCsvWriter;
import io.github.ecommercebench.app.log.DailyBalance;
import io.github.ecommercebench.app.log.JsonlMessageWriter;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.evaluation.io.BalanceCsvReader;
import io.github.ecommercebench.evaluation.model.BalancePoint;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Python↔Java 双向兼容验收（Plan 05 Task 10 Step 1-2）。
 *
 * <p>Step 1：用 Python 写出的余额 CSV 交给 Java {@link BalanceCsvReader} 读取并校验值。Step 2：用 Java 写入器产出 balance
 * CSV + messages JSONL，再以 {@link ProcessBuilder} 运行仓库现有 {@code evaluation/plot_daily_balance.py} 与
 * {@code evaluation/extract_chatbox.py} 读取，断言退出码 0——证明 Java 产物可被 Python 工具链消费。 若 Python 或依赖不可用，测试显式
 * SKIP（不静默通过）。
 */
class PythonCompatibilityTest {

  private static final Path REPO = Path.of(System.getProperty("user.dir"), "..", "..").normalize();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static boolean pythonReady() {
    try {
      Process process =
          new ProcessBuilder("python3", "-c", "import pandas, matplotlib")
              .redirectErrorStream(true)
              .start();
      process.getInputStream().readAllBytes();
      return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (Exception exception) {
      return false;
    }
  }

  private static int runPython(String... args) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
    builder.environment().put("MPLBACKEND", "Agg");
    Process process = builder.start();
    process.getInputStream().readAllBytes();
    process.waitFor(180, TimeUnit.SECONDS);
    return process.exitValue();
  }

  @Test
  void javaReadsPythonProducedBalanceCsv(@TempDir Path tempDir) throws Exception {
    Assumptions.assumeTrue(pythonReady(), "Python/pandas/matplotlib 不可用，跳过兼容测试");

    String header =
        String.join(
            ",",
            "date",
            "bank_balance",
            "platform_wallet",
            "total_balance",
            "open_stores",
            "warehouse_items",
            "storage_charged");
    Path script = tempDir.resolve("gen.py");
    Files.writeString(
        script,
        "import sys\n"
            + "rows = ['"
            + header
            + "',\n"
            + " '2026-01-01,100000.0,0.0,100000.0,0,0,0',\n"
            + " '2026-01-02,99000.0,500.0,99500.0,1,10,5.0']\n"
            + "open(sys.argv[1],'w').write('\\n'.join(rows)+'\\n')\n");
    Path csv = tempDir.resolve("py_balance.csv");
    assertThat(runPython("python3", script.toString(), csv.toString())).isZero();

    List<BalancePoint> points = new BalanceCsvReader().read(csv);
    assertThat(points).hasSize(2);
    assertThat(points.get(1).totalBalance()).isEqualTo(99500.0);
    assertThat(points.get(1).warehouseItems()).isEqualTo(10);
  }

  @Test
  void pythonEvaluationScriptsReadJavaArtifacts(@TempDir Path tempDir) throws Exception {
    Assumptions.assumeTrue(pythonReady(), "Python/pandas/matplotlib 不可用，跳过兼容测试");
    Path plotScript = REPO.resolve("evaluation/plot_daily_balance.py");
    Path extractScript = REPO.resolve("evaluation/extract_chatbox.py");
    Assumptions.assumeTrue(
        Files.isRegularFile(plotScript) && Files.isRegularFile(extractScript),
        "仓库根 evaluation/*.py 不存在（java-impl 独立成库场景），跳过 Python 兼容测试");

    Path session = tempDir.resolve("20260101_080000_compat");
    Files.createDirectories(session.resolve("balance"));
    Files.createDirectories(session.resolve("trajectories"));

    Path csv = session.resolve("balance/run_0_daily_balance.csv");
    try (BalanceCsvWriter writer = new BalanceCsvWriter(csv)) {
      writer.writeRow(
          new DailyBalance(
              LocalDate.of(2026, 1, 1),
              Money.of("100000"),
              Money.ZERO,
              Money.of("100000"),
              0,
              0,
              Money.ZERO));
      writer.writeRow(
          new DailyBalance(
              LocalDate.of(2026, 1, 2),
              Money.of("99000"),
              Money.of("500"),
              Money.of("99500"),
              1,
              10,
              Money.of("5")));
    }

    Path jsonl = session.resolve("trajectories/run_0_messages.jsonl");
    try (JsonlMessageWriter writer = new JsonlMessageWriter(jsonl, MAPPER)) {
      writer.writeMessage(
          ChatMessage.assistant(
              "",
              List.of(
                  new ToolCall("t1", "chatbox", MAPPER.readTree("{\"uid\":\"a@x.com\"}"), null)),
              null,
              List.of()));
      writer.writeToolResult(
          new ToolExecutionResult("t1", "chatbox", "{\"message\":\"message_sent\"}"));
    }

    Path plots = tempDir.resolve("plots");
    int plotExit =
        runPython(
            "python3", plotScript.toString(), csv.toString(), "--output-dir", plots.toString());
    assertThat(plotExit).isZero();

    Path chatbox = tempDir.resolve("chatbox");
    int extractExit =
        runPython(
            "python3",
            extractScript.toString(),
            session.toString(),
            "--output-dir",
            chatbox.toString());
    assertThat(extractExit).isZero();
  }
}
