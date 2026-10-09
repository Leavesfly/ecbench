package io.github.ecommercebench.evaluation.model;

import java.util.List;

/**
 * 多会话指标比较报告，端口 Python {@code compare_runs.render_comparison} 的数据部分。
 *
 * <p>每个会话按 {@code METRIC_DEFS} 的 JSON path 聚合其所有 run 的 analysis：{@code mean}/{@code std}
 * 为样本均值与样本标准差（n-1）， 无数值样本时为 null（对应非数值指标，用 {@code fallback} 文本表示）。null 指标被跳过而非计为 0。
 */
public record ComparisonReport(List<SessionComparison> sessions) {

    public ComparisonReport {
        sessions = List.copyOf(sessions);
    }

    /**
     * 单个会话的聚合：名称、run 数与各指标统计。
     */
    public record SessionComparison(String name, int runCount, List<MetricStat> metrics) {

        public SessionComparison {
            metrics = List.copyOf(metrics);
        }

        public MetricStat metric(String label) {
            return metrics.stream().filter(m -> m.label().equals(label)).findFirst().orElse(null);
        }
    }

    /**
     * 单个指标的均值/标准差/样本数；非数值时 mean/std 为 null 且 fallback 承载文本值。
     */
    public record MetricStat(String label, Double mean, Double std, int n, String fallback) {
    }
}
