package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;

/**
 * 论文 §3.7 / Figure 2 的七个评估轴（主要得分 + 顺时针 6 维），按固定顺序排列。
 *
 * <p>每轴从会话聚合的 {@link MetricStat} 均值取原始值。欺诈规避、偿付能力、运营执行为「越低越好」（归一化时翻转）； 学习轴用 {@code se_half_lift}
 * 代理（越高越好，方向与论文 AnchorRatio 相反，属 spec 记录的偏差）。派生的偿付/效率轴在分母为 0 或缺样本时返回 {@code null}。
 */
public enum ProfileAxis {
    PRIMARY("primary", "Primary score", false),
    NEGOTIATION("negotiation", "Negotiation quality", false),
    FRAUD("fraud", "Fraud avoidance", true),
    SOLVENCY("solvency", "Cash flow & solvency", true),
    EFFICIENCY("efficiency", "Operational efficiency", false),
    EXECUTION("execution", "Operations execution", true),
    LEARNING("learning", "Learning over horizon (se_half_lift proxy)", false);

    private final String key;
    private final String label;
    private final boolean lowerIsBetter;

    ProfileAxis(String key, String label, boolean lowerIsBetter) {
        this.key = key;
        this.label = label;
        this.lowerIsBetter = lowerIsBetter;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /**
     * 归一化时是否需要翻转：true 表示原始值越低越好。
     */
    public boolean lowerIsBetter() {
        return lowerIsBetter;
    }

    /**
     * 该轴在给定会话上的原始值；缺样本或分母为 0 时返回 {@code null}。
     */
    public Double value(SessionComparison session) {
        return switch (this) {
            case PRIMARY -> mean(session, "final_balance");
            case NEGOTIATION -> mean(session, "CSE+");
            case FRAUD -> mean(session, "spend_bad_share");
            case SOLVENCY -> ratio(mean(session, "peak_drawdown"), mean(session, "peak_total_assets"));
            case EFFICIENCY -> ratio(
                    difference(mean(session, "final_balance"), mean(session, "initial_balance")),
                    mean(session, "tool_calls"));
            case EXECUTION -> mean(session, "controllable_return_rate");
            case LEARNING -> mean(session, "se_half_lift");
        };
    }

    private static Double mean(SessionComparison session, String label) {
        MetricStat stat = session.metric(label);
        return stat == null ? null : stat.mean();
    }

    private static Double difference(Double minuend, Double subtrahend) {
        return minuend == null || subtrahend == null ? null : minuend - subtrahend;
    }

    private static Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0.0) {
            return null;
        }
        return numerator / denominator;
    }
}
