package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;
import io.github.ecommercebench.evaluation.model.NormalizedProfile.ModelProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把 {@link ComparisonReport} 转为跨模型归一化画像（论文 §3.7 / Figure 2）。
 *
 * <p>每轴：取各模型原始值 → min-max 归一化到 [0,1]（「越低越好」轴翻转，使离中心越远越好）→ {@code hi==lo}（含单模型）退化取 1.0。 缺样本记为
 * NaN，不参与 lo/hi 与中位数；中位数多边形取各轴非 NaN 归一化值的中位数（偶数个取中间两值均值，全缺取 0.0）。
 */
public final class CrossModelProfiler {

    public NormalizedProfile profile(ComparisonReport report) {
        List<SessionComparison> sessions = report.sessions();
        ProfileAxis[] axes = ProfileAxis.values();
        int axisCount = axes.length;
        int modelCount = sessions.size();

        double[][] raw = new double[modelCount][axisCount];
        for (int m = 0; m < modelCount; m++) {
            for (int a = 0; a < axisCount; a++) {
                Double value = axes[a].value(sessions.get(m));
                raw[m][a] = value == null ? Double.NaN : value;
            }
        }

        double[][] normalized = new double[modelCount][axisCount];
        List<Double> median = new ArrayList<>(axisCount);
        for (int a = 0; a < axisCount; a++) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            for (int m = 0; m < modelCount; m++) {
                if (!Double.isNaN(raw[m][a])) {
                    lo = Math.min(lo, raw[m][a]);
                    hi = Math.max(hi, raw[m][a]);
                }
            }
            List<Double> present = new ArrayList<>();
            for (int m = 0; m < modelCount; m++) {
                double value = raw[m][a];
                if (Double.isNaN(value)) {
                    normalized[m][a] = Double.NaN;
                    continue;
                }
                double scaled = scale(value, lo, hi, axes[a].lowerIsBetter());
                normalized[m][a] = scaled;
                present.add(scaled);
            }
            median.add(median(present));
        }

        List<ModelProfile> models = new ArrayList<>(modelCount);
        for (int m = 0; m < modelCount; m++) {
            models.add(new ModelProfile(sessions.get(m).name(), toList(raw[m]), toList(normalized[m])));
        }
        return new NormalizedProfile(models, median);
    }

    private static double scale(double value, double lo, double hi, boolean lowerIsBetter) {
        if (hi == lo) {
            return 1.0;
        }
        return lowerIsBetter ? (hi - value) / (hi - lo) : (value - lo) / (hi - lo);
    }

    private static List<Double> toList(double[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double value : values) {
            list.add(value);
        }
        return list;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
