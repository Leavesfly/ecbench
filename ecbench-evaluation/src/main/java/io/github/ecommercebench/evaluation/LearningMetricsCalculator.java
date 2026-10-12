package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 论文 §E.6「长周期学习」指标计算器（纯函数，无 IO、无静态可变状态）。
 *
 * <p>输入为一个 episode 的逐谈判记录，输出 {@link LearningMetrics}。序贯价格纪律部分：只保留诚信、已成交、有结束日、
 * 价格字段齐备且议价区间 {@code Δ = 参考价 − 成本底价 > 0} 的 (supplier, SKU) pair，且要求该 pair 至少成交两次；
 * 把每次成交价映射为区间内位置 {@code π}（截断到 [0,1]，与论文「positions here are clipped, unlike SE」一致），
 * 从第二笔起与该 pair 的历史最优 {@code m} 比较，得到 {@code AnchorRegret} 与创新低率 {@code NewLow}（容差 ε=0.01）。
 *
 * <p>确定性保证：pair 迭代序为 key（supplier 与 skuId 以 NUL 分隔拼接，与 {@code NegotiationTracker} 的键约定一致）的
 * 字典序（{@link TreeMap}），pair 内按结束日升序且同日保持输入序（{@link List#sort} 稳定），因此同输入逐位可复现。
 * 本类不做四舍五入，展示层负责取整口径。
 */
public final class LearningMetricsCalculator {

    /** 论文 §E.6 的创新低容差 ε。 */
    private static final double EPSILON = 0.01;

    /** 计算一个 episode 的 §E.6 指标；不适用者为 null。 */
    public LearningMetrics calculate(List<NegotiationRecordRow> rows) {
        List<Pair> pairs = honestRepeatPairs(rows);
        if (pairs.isEmpty()) {
            return new LearningMetrics(
                    null, null, null, null, null, null, null, null, null, null, 0, 0);
        }
        int reorders = pairs.stream().mapToInt(Pair::reorders).sum();
        double regret = levelStatistic(pairs, true);
        double newLow = levelStatistic(pairs, false);
        return new LearningMetrics(
                regret,
                newLow,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                pairs.size(),
                reorders);
    }

    /**
     * 一个诚信 (supplier, SKU) pair 的位置序列。
     *
     * @param key 字典序排序键（supplier 与 skuId 以 NUL 分隔）
     * @param positions 按结束日升序的截断位置 π
     */
    private record Pair(String key, double[] positions) {

        int reorders() {
            return positions.length - 1;
        }
    }

    /** 应用论文 §E.6 的四项过滤，返回按 key 字典序排列的重复采购 pair。 */
    private static List<Pair> honestRepeatPairs(List<NegotiationRecordRow> rows) {
        Map<String, List<NegotiationRecordRow>> grouped = new TreeMap<>();
        for (NegotiationRecordRow row : rows) {
            if (!usableForAnchoring(row)) {
                continue;
            }
            grouped.computeIfAbsent(row.supplier() + "\u0000" + row.skuId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        List<Pair> pairs = new ArrayList<>();
        for (Map.Entry<String, List<NegotiationRecordRow>> entry : grouped.entrySet()) {
            List<NegotiationRecordRow> records = entry.getValue();
            if (records.size() < 2) {
                continue;
            }
            // List.sort 是稳定排序：同一结束日保持 jsonl 出现序
            records.sort(Comparator.comparing(NegotiationRecordRow::dayConcluded));
            double floor = records.get(0).costFloor();
            // 区间取该 pair 首笔的值：底价与参考价都属于 SKU，跨周期不变（论文 §E.6）
            double delta = records.get(0).referencePrice() - floor;
            double[] positions = new double[records.size()];
            for (int i = 0; i < records.size(); i++) {
                positions[i] = clip((records.get(i).finalPrice() - floor) / delta);
            }
            pairs.add(new Pair(entry.getKey(), positions));
        }
        return pairs;
    }

    /** §E.6 过滤规则 1–3 外加价格字段齐备：诚信、已成交且有成交价、有结束日、议价区间为正。 */
    private static boolean usableForAnchoring(NegotiationRecordRow row) {
        return "good".equals(row.supplierType())
                && row.isAgreement()
                && row.dayConcluded() != null
                && row.referencePrice() != null
                && row.costFloor() != null
                && row.referencePrice() - row.costFloor() > 0.0;
    }

    /** 截断到 [0,1]。 */
    private static double clip(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 跨 pair 汇总水平统计量：regret=true 取 max(0, π_t − m_t)，否则取 1[π_t &lt; m_t − ε]；分母为 |R|。 */
    private static double levelStatistic(List<Pair> pairs, boolean regret) {
        double sum = 0.0;
        int count = 0;
        for (Pair pair : pairs) {
            double[] part = accumulate(pair.positions(), regret);
            sum += part[0];
            count += (int) part[1];
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /** 对单条位置序列累计 re-order 统计量，返回 [求和, 计数]；历史最优 m 随扫描推进。 */
    private static double[] accumulate(double[] positions, boolean regret) {
        double sum = 0.0;
        int count = 0;
        double best = positions[0];
        for (int t = 1; t < positions.length; t++) {
            double current = positions[t];
            sum += regret ? Math.max(0.0, current - best) : (current < best - EPSILON ? 1.0 : 0.0);
            count++;
            best = Math.min(best, current);
        }
        return new double[] {sum, count};
    }
}
