package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.evaluation.BalancePlotter.BalanceStatistics;
import io.github.ecommercebench.evaluation.BalancePlotter.PlotOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BalancePlotter 测试：输出合法 PNG（magic 89 50 4E 47），并对多曲线最终余额计算均值/标准差/稳定性。
 */
class BalancePlotterTest {

    private static Path writeBalance(Path dir, String runName, double... totals) throws Exception {
        StringBuilder csv =
                new StringBuilder(
                        "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,"
                                + "storage_charged\n");
        for (int i = 0; i < totals.length; i++) {
            csv.append("2026-01-0")
                    .append(i + 1)
                    .append(",0,0,")
                    .append(String.format("%.2f", totals[i]))
                    .append(",0,0,0\n");
        }
        Path file = dir.resolve(runName + "_daily_balance.csv");
        Files.writeString(file, csv.toString());
        return file;
    }

    @Test
    void writesValidPngAndSummarizesFinalBalances(@TempDir Path tempDir) throws Exception {
        Path csv0 = writeBalance(tempDir, "run_0", 1000, 1200, 1500);
        Path csv1 = writeBalance(tempDir, "run_1", 1000, 900, 1100);
        BalancePlotter plotter = new BalancePlotter();

        Path written =
                plotter.plot(
                        List.of(csv0, csv1), new PlotOptions(tempDir.resolve("plots/balance.png"), "t", true));

        assertThat(Files.exists(written)).isTrue();
        byte[] header = Arrays.copyOf(Files.readAllBytes(written), 4);
        assertThat(header[0] & 0xFF).isEqualTo(0x89);
        assertThat(header[1] & 0xFF).isEqualTo(0x50);
        assertThat(header[2] & 0xFF).isEqualTo(0x4E);
        assertThat(header[3] & 0xFF).isEqualTo(0x47);

        BalanceStatistics stats = plotter.summarize(List.of(csv0, csv1));
        assertThat(stats.runs()).isEqualTo(2);
        assertThat(stats.mean()).isEqualTo(1300.0);
        assertThat(stats.std()).isGreaterThan(0.0);
        assertThat(stats.stability()).isBetween(0.0, 1.0);
    }

    @Test
    void pngReturnsValidPngBytesWithoutTouchingDisk(@TempDir Path tempDir) throws Exception {
        Path csv0 = writeBalance(tempDir, "run_0", 1000, 1200, 1500);
        Path csv1 = writeBalance(tempDir, "run_1", 1000, 900, 1100);
        BalancePlotter plotter = new BalancePlotter();

        byte[] png = plotter.png(List.of(csv0, csv1), List.of("2026_a/run_0", "2026_b/run_0"), "t");

        assertThat(png.length).isGreaterThan(0);
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1] & 0xFF).isEqualTo(0x50);
        assertThat(png[2] & 0xFF).isEqualTo(0x4E);
        assertThat(png[3] & 0xFF).isEqualTo(0x47);
        // 不落盘：临时目录里只应有两个 CSV，没有任何 PNG
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.filter(file -> file.toString().endsWith(".png")).count()).isZero();
        }
    }

    @Test
    void pngRejectsMismatchedLabelsAndEmptyInput(@TempDir Path tempDir) throws Exception {
        Path csv0 = writeBalance(tempDir, "run_0", 1000, 1200);
        BalancePlotter plotter = new BalancePlotter();

        assertThatThrownBy(() -> plotter.png(List.of(csv0), List.of("a", "b"), "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不一致");
        assertThatThrownBy(() -> plotter.png(List.of(), List.of(), "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可绘制的余额 CSV");
    }
}
