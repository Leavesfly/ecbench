package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 论文 §E.6「长周期学习」指标计算器（纯函数，无 IO、无静态可变状态）。
 *
 * <p>输入为一个 episode 的逐谈判记录，输出 {@link LearningMetrics}。序贯价格纪律部分：只保留诚信、已成交、有结束日、
 * 价格字段齐备且议价区间 {@code Δ = 参考价 − 成本底价 > 0} 的 (supplier, SKU) pair，且要求该 pair 至少成交两次；
 * 把每次成交价映射为区间内位置 {@code π}（截断到 [0,1]，与论文「positions here are clipped, unlike SE」一致），
 * 从第二笔起与该 pair 的历史最优 {@code m} 比较，得到 {@code AnchorRegret} 与创新低率 {@code NewLow}（容差 ε=0.01）；
 * 再用 B=400 次固定种子的独立置换构造零分布，得到 {@code AnchorRatio}（观测值 ÷ 零均值）与置换 z。
 *
 * <p>半年比较部分（同属 §E.6）按时间序而非按供应商分组读数：剩余半期提升 {@code h_SE}（诚信已结束会话按结束日排序后
 * 对半切，n ≥ 4 才算，未成交记 0 分）、逐日盈余趋势 {@code b_SE}（在整个诚信集合上最小二乘，全同日记 0）、
 * 欺诈半期提升 {@code h_fraud}（在全部已结束会话上按自身中位日切分，早半减晚半）。三者均以「正 = 改善」为方向。
 *
 * <p>确定性保证：pair 迭代序为 key（supplier 与 skuId 以 NUL 分隔拼接，与 {@code NegotiationTracker} 的键约定一致）的
 * 字典序（{@link TreeMap}），pair 内按结束日升序且同日保持输入序（{@link List#sort} 稳定），置换用单个共享
 * {@link Random}（默认种子 20260101），因此同输入 + 同种子逐位可复现。零分布的离散度是**总体**标准差（除以 B），
 * 与档案其他处的样本标准差刻意不同，不可互比。本类不做四舍五入，展示层负责取整口径。
 */
public final class LearningMetricsCalculator {

    /** 论文 §E.6 的创新低容差 ε。 */
    private static final double EPSILON = 0.01;

    /** 论文 §E.6 的置换重复次数 B。 */
    private static final int DEFAULT_REPLICATES = 400;

    /** 默认随机种子：保证同一产物多次计算结果逐位一致。 */
    private static final long DEFAULT_SEED = 20260101L;

    /** 半年比较所需的最小诚信已结束会话数 n（论文 §E.6：n ≥ 4）。 */
    private static final int MIN_SESSIONS_FOR_HALF = 4;

    private final long seed;
    private final int replicates;

    public LearningMetricsCalculator() {
        this(DEFAULT_SEED, DEFAULT_REPLICATES);
    }

    public LearningMetricsCalculator(long seed, int replicates) {
        this.seed = seed;
        this.replicates = replicates;
    }

    /** 计算一个 episode 的 §E.6 指标；不适用者为 null。 */
    public LearningMetrics calculate(List<NegotiationRecordRow> rows) {
        List<Pair> pairs = honestRepeatPairs(rows);
        if (pairs.isEmpty()) {
            // 半年比较不依赖重复 pair：无 anchoring 样本时仍须计算（否则只有单次采购的 episode 会丢掉三项趋势指标）
            List<NegotiationRecordRow> honestOnly = honestConcluded(rows);
            return new LearningMetrics(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    surplusHalfLift(honestOnly),
                    surplusTrend(honestOnly),
                    fraudHalfLift(rows),
                    0,
                    0);
        }
        int reorders = pairs.stream().mapToInt(Pair::reorders).sum();
        double regret = levelStatistic(pairs, true);
        double newLow = levelStatistic(pairs, false);
        double[] nullRegret = permutationNull(pairs, true);
        double[] nullNewLow = permutationNull(pairs, false);
        Double ratio = nullRegret[0] == 0.0 ? null : regret / nullRegret[0];
        Double zEpisode = nullRegret[1] == 0.0 ? null : (nullRegret[0] - regret) / nullRegret[1];
        Double newLowZ = nullNewLow[1] == 0.0 ? null : (newLow - nullNewLow[0]) / nullNewLow[1];
        List<NegotiationRecordRow> honest = honestConcluded(rows);
        return new LearningMetrics(
                regret,
                newLow,
                ratio,
                zEpisode,
                newLowZ,
                nullRegret[0],
                nullRegret[1],
                surplusHalfLift(honest),
                surplusTrend(honest),
                fraudHalfLift(rows),
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

    /**
     * 构造论文 §E.6 的置换零分布：B 次重复，每次对**每个 pair 独立**洗牌其位置序列后重算同一统计量。
     *
     * <p>单个共享 {@link Random}（固定种子）按 pair 字典序依次洗牌，因此结果逐位可复现。返回 [均值, 总体标准差]。
     */
    private double[] permutationNull(List<Pair> pairs, boolean regret) {
        Random random = new Random(seed);
        double[] values = new double[replicates];
        for (int b = 0; b < replicates; b++) {
            List<Pair> shuffled = new ArrayList<>(pairs.size());
            for (Pair pair : pairs) {
                shuffled.add(new Pair(pair.key(), shuffle(pair.positions(), random)));
            }
            values[b] = levelStatistic(shuffled, regret);
        }
        return moments(values);
    }

    /** Fisher–Yates 洗牌副本，使用共享 Random 保证同种子下的确定性。 */
    private static double[] shuffle(double[] source, Random random) {
        double[] copy = source.clone();
        for (int i = copy.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            double swap = copy[i];
            copy[i] = copy[j];
            copy[j] = swap;
        }
        return copy;
    }

    /** 均值与**总体**标准差（除以 B，论文 §E.6），返回 [mean, populationStd]。 */
    private static double[] moments(double[] values) {
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;
        double sumSquares = 0.0;
        for (double value : values) {
            sumSquares += (value - mean) * (value - mean);
        }
        return new double[] {mean, Math.sqrt(sumSquares / values.length)};
    }

    /** §E.6 的 Z_G：诚信且已结束的会话（成交与未成交都算），按结束日升序（稳定排序）。 */
    private static List<NegotiationRecordRow> honestConcluded(List<NegotiationRecordRow> rows) {
        List<NegotiationRecordRow> out = new ArrayList<>();
        for (NegotiationRecordRow row : rows) {
            if ("good".equals(row.supplierType()) && row.dayConcluded() != null) {
                out.add(row);
            }
        }
        out.sort(Comparator.comparing(NegotiationRecordRow::dayConcluded));
        return out;
    }

    /** s_z = 1[成交] · SE_z；SE_z **不截断**（与位置 π 不同），Δ ≤ 0 或价格缺失记 0。 */
    private static double surplusScore(NegotiationRecordRow row) {
        if (!row.isAgreement() || row.referencePrice() == null || row.costFloor() == null) {
            return 0.0;
        }
        double delta = row.referencePrice() - row.costFloor();
        return delta <= 0.0 ? 0.0 : (row.referencePrice() - row.finalPrice()) / delta;
    }

    /** h_SE = 晚半均值 − 早半均值；n &lt; 4 时不适用。奇数 n 时多的一个归晚半。 */
    private static Double surplusHalfLift(List<NegotiationRecordRow> honest) {
        if (honest.size() < MIN_SESSIONS_FOR_HALF) {
            return null;
        }
        List<Double> scores = new ArrayList<>(honest.size());
        for (NegotiationRecordRow row : honest) {
            scores.add(surplusScore(row));
        }
        int middle = honest.size() / 2;
        return mean(scores.subList(middle, scores.size())) - mean(scores.subList(0, middle));
    }

    /** b_SE = s_z 对 d_z 的最小二乘斜率（在整个诚信集合上拟合）；全部同日 → 0；无会话 → null。 */
    private static Double surplusTrend(List<NegotiationRecordRow> honest) {
        if (honest.isEmpty()) {
            return null;
        }
        double meanDay = 0.0;
        double meanScore = 0.0;
        for (NegotiationRecordRow row : honest) {
            meanDay += row.dayConcluded();
            meanScore += surplusScore(row);
        }
        meanDay /= honest.size();
        meanScore /= honest.size();
        double numerator = 0.0;
        double denominator = 0.0;
        for (NegotiationRecordRow row : honest) {
            double dayDelta = row.dayConcluded() - meanDay;
            numerator += dayDelta * (surplusScore(row) - meanScore);
            denominator += dayDelta * dayDelta;
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    /**
     * h_fraud = β(早半) − β(晚半)，在**全部**已结束会话上按其中位日切分；β 为该半期内与欺诈方成交的会话占比。
     *
     * <p>正 = 改善（晚半更少与欺诈方成交）。样本 &lt; 4、或切分后任一半为空（典型情形：全部会话同日）→ null。
     */
    private static Double fraudHalfLift(List<NegotiationRecordRow> rows) {
        List<NegotiationRecordRow> concluded = new ArrayList<>();
        for (NegotiationRecordRow row : rows) {
            if (row.dayConcluded() != null) {
                concluded.add(row);
            }
        }
        if (concluded.size() < MIN_SESSIONS_FOR_HALF) {
            return null;
        }
        concluded.sort(Comparator.comparing(NegotiationRecordRow::dayConcluded));
        int median = medianDay(concluded);
        List<NegotiationRecordRow> early = new ArrayList<>();
        List<NegotiationRecordRow> late = new ArrayList<>();
        for (NegotiationRecordRow row : concluded) {
            if (row.dayConcluded() <= median) {
                early.add(row);
            } else {
                late.add(row);
            }
        }
        if (early.isEmpty() || late.isEmpty()) {
            return null;
        }
        return fraudDealShare(early) - fraudDealShare(late);
    }

    /** 中位日：奇数取中间值，偶数取中间两值的整数均值（切分规则见 {@link #fraudHalfLift}）。 */
    private static int medianDay(List<NegotiationRecordRow> concluded) {
        int n = concluded.size();
        int mid = n / 2;
        return n % 2 == 1
                ? concluded.get(mid).dayConcluded()
                : (concluded.get(mid - 1).dayConcluded() + concluded.get(mid).dayConcluded()) / 2;
    }

    /** β(S) = S 中与欺诈供应商成交的会话数 / |S|。 */
    private static double fraudDealShare(List<NegotiationRecordRow> sessions) {
        long fraudDeals =
                sessions.stream()
                        .filter(row -> "bad".equals(row.supplierType()))
                        .filter(NegotiationRecordRow::isAgreement)
                        .count();
        return (double) fraudDeals / sessions.size();
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }
}
