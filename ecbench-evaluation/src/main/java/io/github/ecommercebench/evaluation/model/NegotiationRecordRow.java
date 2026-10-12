package io.github.ecommercebench.evaluation.model;

import java.util.List;

/**
 * 逐谈判原始记录 JSONL 的一行（{@code metrics/run_{i}_negotiation_records.jsonl}）。
 *
 * <p>由 {@code ecbench-app} 的写入器在 run 收尾时落盘到 metrics 目录下的逐谈判记录文件，供评估层按论文 §E.6
 * 重算学习维度指标。价格字段以 {@code Double} 承载：{@code finalPrice} 为 null 表示未成交（论文的 {@code f_z = ⊥}），
 * {@code dayConcluded} 为 null 表示无结束日（§E.6 过滤规则 2 会剔除）。三个列表组件在构造器内做 null 归一与不可变拷贝。
 */
public record NegotiationRecordRow(
        String supplier,
        String skuId,
        String supplierType,
        String supplierFamily,
        Double referencePrice,
        Double costFloor,
        Double initialOffer,
        Double finalPrice,
        String outcome,
        String terminatedBy,
        Integer dayStarted,
        Integer dayConcluded,
        Integer rounds,
        List<String> criticalViolations,
        List<Double> agentPrices,
        List<Double> supplierPrices) {

    public NegotiationRecordRow {
        criticalViolations = criticalViolations == null ? List.of() : List.copyOf(criticalViolations);
        agentPrices = agentPrices == null ? List.of() : List.copyOf(agentPrices);
        supplierPrices = supplierPrices == null ? List.of() : List.copyOf(supplierPrices);
    }

    /** 是否为达成一致的成交（结局为 Agreement 且记录了成交价）。 */
    public boolean isAgreement() {
        return "Agreement".equals(outcome) && finalPrice != null;
    }
}
