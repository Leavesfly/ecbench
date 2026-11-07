package io.github.ecommercebench.opponent.metrics;

import java.util.Map;

/**
 * TERMS Bench 聚合指标快照，由 {@code NegotiationTracker#aggregate()} 汇总全部已完成谈判产出。
 *
 * <p>语义约定：成交指结局为 Agreement 且带成交价的谈判；好/坏供应商按 supplierType 分组；
 * 剩余效率以（参考价−成本底线）为跨度衡量成交价的节省比例，未成交计 0。
 * 样本不足而无法计算的指标用 null 表示（落盘为 JSON null），非 null 的比率取值范围为 [0,1]。
 *
 * @param completedNegotiations    已完成（已结束并被归集）的谈判总条数，作为其余指标的样本基数
 * @param agrPlus                  好供应商成交率 AGR+：good 组内达成一致的占比，无 good 样本时为 null
 * @param fagrMinus                坏供应商成交率 FAGR-：bad 组内达成一致的占比，越低说明越能避开坏供应商；
 *                                 无 bad 样本时为 null
 * @param sePlus                   平均剩余效率 SE+：good 组全体谈判的平均剩余效率（未成交按 0 计入），无样本时为 null
 * @param csePlus                  条件平均剩余效率 CSE+：仅统计 good 组中已成交谈判的平均剩余效率，无成交时为 null
 * @param percentOracle            %Oracle 指标：理想化最优成交的表现值，当前聚合实现复用 CSE+ 的取值
 * @param criticalViolationRate    关键违规率 CritViol：含关键违规条款的谈判占全部已完成谈判的比例
 * @param avgRoundsToDeal          已成交谈判的平均回合数（保留两位小数，无成交时为 0）
 * @param totalMoneySavedVsInitial 已成交谈判相对各自初始报价累计节省的金额（保留两位小数）
 * @param learning                 学习速度指标：{@code se_half_lift} 为后半段相对前半段的剩余效率提升，
 *                                 {@code good_negotiations} 为参与统计的样本数；样本少于 4 条时为空 Map
 * @param anchoring                锚定效应指标：{@code anchor_regret} 为重复采购相对历史最低价的平均后悔值，
 *                                 {@code events} 为比较事件数；无重复成交时为空 Map
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
    /**
     * 紧凑构造器：把可能为 null 的学习/锚定指标串规整为不可变空 Map，并做防御性拷贝，
     * 避免调用方后续修改原集合影响指标快照。
     */
    public NegotiationMetrics {
        // 样本不足时上游返回 null，此处统一收口为空 Map，保证下游序列化不出现 NPE
        learning = learning == null ? Map.of() : Map.copyOf(learning);
        anchoring = anchoring == null ? Map.of() : Map.copyOf(anchoring);
    }
}
