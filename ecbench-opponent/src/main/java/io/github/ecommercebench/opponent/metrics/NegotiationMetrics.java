package io.github.ecommercebench.opponent.metrics;

import java.util.Map;

/**
 * TERMS Bench 聚合指标；样本不足的指标使用 null。
 */
public record NegotiationMetrics(
        int completedNegotiations,
        Double agrPlus,
        Double fagrMinus,
        Double sePlus,
        Double csePlus,
        Double percentOracle,
        Double criticalViolationRate,
        double avgRoundsToDeal,
        double totalMoneySavedVsInitial,
        Map<String, Object> learning,
        Map<String, Object> anchoring) {
    public NegotiationMetrics {
        learning = learning == null ? Map.of() : Map.copyOf(learning);
        anchoring = anchoring == null ? Map.of() : Map.copyOf(anchoring);
    }
}
