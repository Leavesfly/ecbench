package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.io.BalanceCsvReader;
import io.github.ecommercebench.evaluation.model.BalancePoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;

/**
 * 余额曲线绘图器，端口 Python {@code plot_daily_balance.plot_balance_overlay} 的核心行为（以 XChart 生成 PNG）。
 *
 * <p>纵轴一律使用权威列 total_balance（bank+wallet+escrow），绝不自行用 bank+wallet 替代。每条 CSV 一条曲线，横轴为日序号。 {@link
 * #summarize} 复现 Python {@code _compute_balance_variance_stats}：对各 run 的最终 total_balance
 * 计算均值/样本标准差/CV/稳定性。
 */
public final class BalancePlotter {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 绘图选项：输出 PNG 路径、标题、是否叠加多曲线。
     */
    public record PlotOptions(Path output, String title, boolean overlay) {
    }

    /**
     * 最终余额的方差统计；stability = 1/(1+cv)，越接近 1 越稳定。
     */
    public record BalanceStatistics(double mean, double std, double cv, double stability, int runs) {
    }

    private final BalanceCsvReader reader;

    public BalancePlotter() {
        this(new BalanceCsvReader());
    }

    public BalancePlotter(BalanceCsvReader reader) {
        this.reader = reader;
    }

    public Path plot(List<Path> csvFiles, PlotOptions options) {
        List<String> labels = new ArrayList<>();
        for (Path csv : csvFiles) {
            labels.add(seriesName(csv));
        }
        XYChart chart = buildChart(csvFiles, labels, options.title());
        try {
            Path parent = options.output().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // XChart 的 saveBitmap 接收不含扩展名的文件名并自行追加 ".png"，故先剥去 .png 再传入。
            String fileName = options.output().toString();
            String base =
                    fileName.toLowerCase().endsWith(".png")
                            ? fileName.substring(0, fileName.length() - 4)
                            : fileName;
            BitmapEncoder.saveBitmap(chart, base, BitmapFormat.PNG);
            return Path.of(base + ".png");
        } catch (IOException exception) {
            throw new UncheckedIOException("无法写出余额图: " + options.output(), exception);
        }
    }

    /**
     * 把余额曲线直接渲染为 PNG 字节，<b>不落盘</b>；供 HTTP 下载端点使用。
     *
     * @param csvFiles 每条曲线一个余额 CSV
     * @param labels 每条曲线的图例名，长度必须与 {@code csvFiles} 相同。多会话叠加时必须带会话前缀，
     *     否则各会话的 {@code run_0} 会同名而在图例里分不清
     * @param title 图标题，{@code null} 时用 {@code "daily balance"}
     * @throws IllegalArgumentException 无 CSV 可绘，或图例名数量与 CSV 数量不一致
     */
    public byte[] png(List<Path> csvFiles, List<String> labels, String title) {
        XYChart chart = buildChart(csvFiles, labels, title);
        try {
            return BitmapEncoder.getBitmapBytes(chart, BitmapFormat.PNG);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法编码余额图 PNG", exception);
        }
    }

    /** 构建 XYChart：每条 CSV 一条曲线，横轴为日序号，纵轴为权威列 total_balance。 */
    private XYChart buildChart(List<Path> csvFiles, List<String> labels, String title) {
        if (csvFiles.isEmpty()) {
            throw new IllegalArgumentException("没有可绘制的余额 CSV");
        }
        if (labels.size() != csvFiles.size()) {
            throw new IllegalArgumentException(
                    "图例名数量 " + labels.size() + " 与 CSV 数量 " + csvFiles.size() + " 不一致");
        }
        XYChart chart =
                new XYChartBuilder()
                        .width(1200)
                        .height(600)
                        .title(title != null ? title : "daily balance")
                        .xAxisTitle("day")
                        .yAxisTitle("total_balance (bank + wallet + escrow)")
                        .build();
        for (int i = 0; i < csvFiles.size(); i++) {
            List<BalancePoint> points = reader.read(csvFiles.get(i));
            List<Double> x = new ArrayList<>();
            List<Double> y = new ArrayList<>();
            for (int day = 0; day < points.size(); day++) {
                x.add((double) day);
                y.add(points.get(day).totalBalance());
            }
            chart.addSeries(labels.get(i), x, y);
        }
        return chart;
    }

    public BalanceStatistics summarize(List<Path> csvFiles) {
        List<Double> finalBalances = new ArrayList<>();
        for (Path csv : csvFiles) {
            List<BalancePoint> points = reader.read(csv);
            if (!points.isEmpty()) {
                finalBalances.add(points.get(points.size() - 1).totalBalance());
            }
        }
        int n = finalBalances.size();
        if (n == 0) {
            return new BalanceStatistics(0.0, 0.0, 0.0, 1.0, 0);
        }
        double mean = finalBalances.stream().mapToDouble(Double::doubleValue).sum() / n;
        if (n == 1) {
            return new BalanceStatistics(mean, 0.0, 0.0, 1.0, 1);
        }
        double sumSquares = 0.0;
        for (double value : finalBalances) {
            sumSquares += (value - mean) * (value - mean);
        }
        double std = Math.sqrt(sumSquares / (n - 1));
        double cv = Math.abs(mean) > 1e-9 ? std / Math.abs(mean) : Double.POSITIVE_INFINITY;
        double stability = 1.0 / (1.0 + cv);
        return new BalanceStatistics(mean, std, cv, stability, n);
    }

    private static String seriesName(Path csv) {
        String name = csv.getFileName().toString();
        return name.endsWith("_daily_balance.csv")
                ? name.substring(0, name.length() - "_daily_balance.csv".length())
                : name;
    }
}
