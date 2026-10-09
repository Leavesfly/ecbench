package io.github.ecommercebench.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code evaluate profile} 子命令冒烟测试：构造两个 session（各含一个 run_*_analysis.json），运行子命令后应产出非空雷达图 PNG、 排名
 * CSV，并返回退出码 0。
 */
class EvaluationCommandProfileTest {

    private static String analysis(
            double finalBalance, double peakAssets, double drawdown, double cse, double badShare) {
        return "{"
                + "\"profitability\":{\"final_balance\":"
                + finalBalance
                + ",\"initial_balance\":100000.0,\"peak_total_assets\":"
                + peakAssets
                + ",\"peak_drawdown\":"
                + drawdown
                + "},"
                + "\"negotiation_quality\":{\"CSE+\":"
                + cse
                + ",\"learning_speed\":{\"se_half_lift\":0.05}},"
                + "\"fraud_identification\":{\"spend_on_bad_supplier_share\":"
                + badShare
                + "},"
                + "\"return_management\":{\"controllable_return_rate\":0.08},"
                + "\"operational_efficiency\":{\"total_tool_calls\":500}"
                + "}";
    }

    private static Path session(Path root, String name, String json) throws Exception {
        Path metrics = root.resolve(name).resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(metrics.resolve("run_0_analysis.json"), json);
        return root.resolve(name);
    }

    @Test
    void profileProducesRadarPngAndRankingCsv(@TempDir Path tempDir) throws Exception {
        Path a = session(tempDir, "modelA", analysis(200000, 250000, 20000, 0.60, 0.10));
        Path b = session(tempDir, "modelB", analysis(140000, 180000, 30000, 0.40, 0.30));
        Path png = tempDir.resolve("out/radar.png");
        Path csv = tempDir.resolve("out/ranking.csv");

        int code =
                new CommandLine(new EvaluationCommand())
                        .execute(
                                "profile",
                                a.toString(),
                                b.toString(),
                                "--output",
                                png.toString(),
                                "--ranking-output",
                                csv.toString());

        assertThat(code).isZero();
        assertThat(png).exists();
        assertThat(Files.size(png)).isGreaterThan(0);
        assertThat(csv).exists();
        assertThat(Files.readString(csv)).startsWith("rank,model,primary_raw");
    }

    @Test
    void profileJsonRankingOutputIsJson(@TempDir Path tempDir) throws Exception {
        Path a = session(tempDir, "modelA", analysis(200000, 250000, 20000, 0.60, 0.10));
        Path png = tempDir.resolve("radar.png");
        Path json = tempDir.resolve("ranking.json");

        int code =
                new CommandLine(new EvaluationCommand())
                        .execute(
                                "profile",
                                a.toString(),
                                "--output",
                                png.toString(),
                                "--ranking-output",
                                json.toString());

        assertThat(code).isZero();
        assertThat(Files.readString(json)).contains("\"axes\"").contains("\"models\"");
    }
}
