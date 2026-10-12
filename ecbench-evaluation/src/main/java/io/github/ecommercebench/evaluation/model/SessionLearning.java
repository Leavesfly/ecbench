package io.github.ecommercebench.evaluation.model;

/**
 * 会话级（跨该会话全部 run）的论文 §E.6 学习维度聚合。
 *
 * <p>按论文口径：{@code anchorRatio} 取各 episode 比率的均值（**不是**汇总均值之比）；{@code zAnchor} 用 Stouffer 法则合并
 * 各 episode 的 z（{@code (1/√k) Σ z_r}）；其余指标取各 episode 均值。{@code episodes} 即论文的 {@code k}：有重复 pair
 * 且零分布非退化的 run 数。null 语义与 {@link LearningMetrics} 一致；全 null 时 {@code episodes} 为 0。
 */
public record SessionLearning(
        Double anchorRatio,
        Double anchorRegret,
        Double newLow,
        Double newLowRatioZ,
        Double zAnchor,
        Double seHalfLift,
        Double seTrend,
        Double fraudHalfLift,
        int episodes,
        int repeatPairs,
        int reorders) {}
