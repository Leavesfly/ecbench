package io.github.ecommercebench.evaluation.model;

/**
 * 单个 episode（= 单个 run）的论文 §E.6 学习维度指标。
 *
 * <p>全部数值字段为 {@code Double} 且以 null 表示「不适用」：该 episode 无重复采购 pair（§E.6 过滤规则 4）、零分布退化
 * （{@code nullMean}/{@code nullStd} 为 0）或半年比较样本不足（诚信已结束会话数 &lt; 4）时即为 null。计数字段恒有值。
 * 本 record 不做四舍五入，展示层负责取整口径。
 */
public record LearningMetrics(
        Double anchorRegret,
        Double newLow,
        Double anchorRatio,
        Double zEpisode,
        Double newLowRatioZ,
        Double nullMean,
        Double nullStd,
        Double seHalfLift,
        Double seTrend,
        Double fraudHalfLift,
        int repeatPairs,
        int reorders) {}
