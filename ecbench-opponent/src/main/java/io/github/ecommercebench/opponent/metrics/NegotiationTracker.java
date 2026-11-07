package io.github.ecommercebench.opponent.metrics;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.model.NegotiationRecord;
import io.github.ecommercebench.opponent.model.SupplierFamily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录全部谈判并计算 TERMS Bench 聚合指标。
 *
 * <p>内部按 {@code supplier\0sku} 复合键分两阶段维护：{@code active} 放进行中的谈判，
 * 结束（成交/谈崩）后由 {@link #recordOutcome} 转入 {@code completed}，
 * 只有后者参与 {@link #aggregate()} 的指标汇总。
 */
public final class NegotiationTracker {

    /**
     * 进行中的谈判，键为 supplier\0sku。
     */
    private final Map<String, NegotiationRecord> active = new LinkedHashMap<>();
    /**
     * 已结束的谈判，按结束先后顺序追加，作为全部指标的样本集。
     */
    private final List<NegotiationRecord> completed = new ArrayList<>();
    /**
     * 随机流，当前仅用于预留锚定指标的排列检验。
     */
    private final RandomStreams randomStreams;

    /**
     * 注入随机流；追踪器本身不持有其他可变配置。
     */
    public NegotiationTracker(RandomStreams randomStreams) {
        this.randomStreams = randomStreams;
    }

    /**
     * 取回进行中的谈判记录，不存在则以给定基准价/底线/初始报价新建。
     *
     * @param supplierName   供应商名称，与 skuId 一起构成复合键
     * @param skuId          商品 ID
     * @param supplierType   供应商类型（good/bad），聚合时用于分组
     * @param family         供应商家族，写入记录供指标分析
     * @param referencePrice 参考价，作为剩余效率与归一化成交价的区间上界
     * @param costFloor      成本底线，作为区间下界
     * @param initialOffer   初始报价，用于统计相对开盘的节省额
     * @param dayStarted     谈判开始日，仅作审计留痕（学习曲线用的是结束日）
     * @return 该键对应的谈判记录（已有则直接返回旧对象）
     */
    public NegotiationRecord getOrCreate(
            String supplierName,
            String skuId,
            String supplierType,
            SupplierFamily family,
            Money referencePrice,
            Money costFloor,
            Money initialOffer,
            int dayStarted) {
        return active.computeIfAbsent(
                key(supplierName, skuId),
                ignored ->
                        new NegotiationRecord(
                                supplierName,
                                skuId,
                                supplierType,
                                family,
                                referencePrice,
                                costFloor,
                                initialOffer,
                                dayStarted));
    }

    /**
     * 向进行中的谈判追加一条 Agent 报价（谈判不存在则忽略）。
     */
    public void recordAgentOffer(String supplier, String sku, Money price) {
        NegotiationRecord record = active.get(key(supplier, sku));
        if (record != null) {
            record.recordAgentOffer(price);
        }
    }

    /**
     * 向进行中的谈判追加一条供应商报价（谈判不存在则忽略）。
     */
    public void recordSupplierOffer(String supplier, String sku, Money price) {
        NegotiationRecord record = active.get(key(supplier, sku));
        if (record != null) {
            record.recordSupplierOffer(price);
        }
    }

    /**
     * 结束一场谈判：从 active 移除、写入结局与终止方，并转入 completed 列表供聚合。
     *
     * @param supplier     供应商名称
     * @param sku          商品 ID
     * @param outcome      结局标记，如 Agreement（成交）或 Disagreement（未谈成）
     * @param price        最终成交价；未成交时传 null
     * @param terminatedBy 终止方，如 SupplierAccept/AgentReject，仅用于审计
     * @param day          结束日，写入记录的 dayConcluded，学习曲线与锚定指标按它排序
     */
    public void recordOutcome(
            String supplier, String sku, String outcome, Money price, String terminatedBy, int day) {
        NegotiationRecord record = active.remove(key(supplier, sku));
        if (record != null) {
            record.complete(outcome, price, terminatedBy, day);
            completed.add(record);
        }
    }

    /**
     * 返回已结束谈判的不可变快照，供审计或外部补充分析。
     */
    public List<NegotiationRecord> completed() {
        return List.copyOf(completed);
    }

    /**
     * 汇总全部已完成谈判，产出 TERMS Bench 指标。
     *
     * <p>good/bad 按 supplierType 分组：agr/fagr 为各自成交率，se/cse 为全体与已成交好供应商的
     * 平均剩余效率，violationRate 为含关键违规的谈判占比， 另给出平均回合数、总节省额及学习与锚定指标。
     *
     * @return 指标快照；任一分组的样本为空时对应字段为 null（由 NegotiationMetrics 约定语义）
     */
    public NegotiationMetrics aggregate() {
        List<NegotiationRecord> good =
                completed.stream().filter(r -> "good".equals(r.supplierType())).toList();
        List<NegotiationRecord> bad =
                completed.stream().filter(r -> "bad".equals(r.supplierType())).toList();
        long goodDeals = good.stream().filter(this::isAgreement).count();
        long badDeals = bad.stream().filter(this::isAgreement).count();
        Double agr = good.isEmpty() ? null : (double) goodDeals / good.size();
        Double fagr = bad.isEmpty() ? null : (double) badDeals / bad.size();
        List<Double> efficiencies = good.stream().map(this::surplusEfficiency).toList();
        List<Double> agreedEfficiencies =
                good.stream().filter(this::isAgreement).map(this::surplusEfficiency).toList();
        Double se = good.isEmpty() ? null : average(efficiencies);
        Double cse = agreedEfficiencies.isEmpty() ? null : average(agreedEfficiencies);
        long violations = completed.stream().filter(r -> !r.criticalViolations().isEmpty()).count();
        Double violationRate = completed.isEmpty() ? null : (double) violations / completed.size();
        List<NegotiationRecord> deals = completed.stream().filter(this::isAgreement).toList();
        double avgRounds =
                deals.isEmpty()
                        ? 0.0
                        : deals.stream().mapToInt(NegotiationRecord::rounds).average().orElse(0.0);
        double totalSaved =
                deals.stream()
                        .mapToDouble(
                                r -> r.initialOffer().amount().subtract(r.finalPrice().amount()).doubleValue())
                        .sum();
        return new NegotiationMetrics(
                completed.size(),
                agr,
                fagr,
                se,
                cse,
                // %Oracle 目前直接复用 CSE+ 的取值，未单独计算
                cse,
                violationRate,
                round2(avgRounds),
                round2(totalSaved),
                learningMetrics(good),
                anchoringMetrics(good));
    }

    /**
     * 学习曲线：把有好供应商成交记录按时间对半切，比较后半与前半的平均剩余效率提升（样本<4 时为空）。
     */
    private Map<String, Object> learningMetrics(List<NegotiationRecord> good) {
        List<NegotiationRecord> timed = good.stream().filter(r -> r.dayConcluded() != null).toList();
        if (timed.size() < 4) {
            return Map.of();
        }
        int middle = timed.size() / 2;
        double early = average(timed.subList(0, middle).stream().map(this::surplusEfficiency).toList());
        double late =
                average(timed.subList(middle, timed.size()).stream().map(this::surplusEfficiency).toList());
        return Map.of("se_half_lift", round4(late - early), "good_negotiations", timed.size());
    }

    /**
     * 计算重复采购相对历史最低价的后悔值；排列检验随机流保留给完整统计扩展。
     */
    private Map<String, Object> anchoringMetrics(List<NegotiationRecord> good) {
        Map<String, List<NegotiationRecord>> grouped = new LinkedHashMap<>();
        good.stream()
                .filter(this::isAgreement)
                .forEach(
                        record ->
                                grouped
                                        .computeIfAbsent(
                                                key(record.supplierName(), record.skuId()),
                                                ignored -> new ArrayList<>())
                                        .add(record));
        List<Double> regrets = new ArrayList<>();
        for (List<NegotiationRecord> records : grouped.values()) {
            if (records.size() < 2) {
                continue;
            }
            records.sort(java.util.Comparator.comparing(NegotiationRecord::dayConcluded));
            double best = normalizedPrice(records.get(0));
            for (int index = 1; index < records.size(); index++) {
                double current = normalizedPrice(records.get(index));
                regrets.add(Math.max(0.0, current - best));
                best = Math.min(best, current);
            }
        }
        // 排列检验随机流已预留但当前结果未使用，仅供后续完整统计扩展
        randomStreams.stream("anchoring-permutation");
        return regrets.isEmpty()
                ? Map.of()
                : Map.of("anchor_regret", round4(average(regrets)), "events", regrets.size());
    }

    /**
     * 判定一场谈判是否为“达成一致的成交”（结局为 Agreement 且有成交价）。
     */
    private boolean isAgreement(NegotiationRecord record) {
        return "Agreement".equals(record.outcome()) && record.finalPrice() != null;
    }

    /**
     * 剩余效率：以(参考价−成本底线)为跨度，成交价相对参考价省下的比例；未成交或跨度非正记 0。
     */
    private double surplusEfficiency(NegotiationRecord record) {
        if (!isAgreement(record)) {
            return 0.0;
        }
        double span =
                record.referencePrice().amount().subtract(record.costFloor().amount()).doubleValue();
        return span <= 0
                ? 0.0
                : record.referencePrice().amount().subtract(record.finalPrice().amount()).doubleValue()
                / span;
    }

    /**
     * 归一化成交价：把最终价映射到 [成本底线, 参考价] 区间的相对位置（限幅 0~1）。
     */
    private double normalizedPrice(NegotiationRecord record) {
        double floor = record.costFloor().amount().doubleValue();
        double span = record.referencePrice().amount().doubleValue() - floor;
        return span <= 0
                ? 0.0
                : Math.max(0.0, Math.min(1.0, (record.finalPrice().amount().doubleValue() - floor) / span));
    }

    /**
     * 求均值；空集合返回 0，避免调用处重复写 orElse。
     */
    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 四舍五入保留 4 位小数，用于率类与后悔值指标。
     */
    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /**
     * 四舍五入保留 2 位小数，用于金额与回合数。
     */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 用 NUL 分隔拼接供应商名与 SKU 作为复合键，避免名称含分隔符时的歧义。
     */
    private static String key(String supplier, String sku) {
        return supplier + "\u0000" + sku;
    }
}
