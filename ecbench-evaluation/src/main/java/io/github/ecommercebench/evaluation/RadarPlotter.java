package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.NormalizedProfile;
import io.github.ecommercebench.evaluation.model.NormalizedProfile.ModelProfile;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.RadarChart;
import org.knowm.xchart.RadarChartBuilder;
import org.knowm.xchart.RadarSeries;

/**
 * 七轴能力画像雷达图绘制器（论文 Figure 2），以 XChart {@link RadarChart} 生成 PNG。
 *
 * <p>每个模型一条多边形（顶点为归一化值，缺样本 NaN 回退到 0），另叠加一条中位数虚线多边形。轴标签取自 {@link ProfileAxis}
 * 的固定顺序，使「离中心越远越好」在各轴一致成立。复用 {@link BalancePlotter} 的 headless + saveBitmap 剥后缀范式。
 */
public final class RadarPlotter {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 中位数多边形的虚线线型。
     */
    private static final BasicStroke DASHED =
            new BasicStroke(
                    2.0f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,
                    10.0f,
                    new float[]{6.0f, 4.0f},
                    0.0f);

    /**
     * 绘图选项：输出 PNG 路径与标题。
     */
    public record RadarOptions(Path output, String title) {
    }

    public Path plot(NormalizedProfile profile, RadarOptions options) {
        if (profile.models().isEmpty()) {
            throw new IllegalArgumentException("没有可绘制的模型画像");
        }
        ProfileAxis[] axes = ProfileAxis.values();
        String[] labels = new String[axes.length];
        for (int i = 0; i < axes.length; i++) {
            labels[i] = axes[i].label();
        }

        RadarChart chart =
                new RadarChartBuilder()
                        .width(900)
                        .height(900)
                        .title(options.title() != null ? options.title() : "model capability profile")
                        .build();
        chart.setRadiiLabels(labels);

        for (ModelProfile model : profile.models()) {
            chart.addSeries(model.name(), toValues(model.normalizedValues()));
        }
        RadarSeries median = chart.addSeries("median", toValues(profile.medianNormalized()));
        median.setLineStyle(DASHED);
        median.setLineColor(Color.DARK_GRAY);

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
            throw new UncheckedIOException("无法写出雷达图: " + options.output(), exception);
        }
    }

    private static double[] toValues(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            double value = values.get(i);
            out[i] = Double.isNaN(value) ? 0.0 : value;
        }
        return out;
    }
}
