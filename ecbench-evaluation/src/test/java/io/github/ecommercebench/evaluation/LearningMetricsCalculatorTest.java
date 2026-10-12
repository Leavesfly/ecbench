package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 论文 §E.6 学习维度计算器测试。
 *
 * <p>主 fixture 为单个诚信 pair（参考价 100、成本底价 50 → Δ=50），三次成交价 90/80/95，对应位置 π=0.8/0.6/0.9：
 * re-order 1 的 max(0, 0.6−0.8)=0 且创新低；re-order 2 的 max(0, 0.9−0.6)=0.3 且未创新低。故
 * AnchorRegret=(0+0.3)/2=0.15、NewLow=(1+0)/2=0.5、|R|=2。
 */
class LearningMetricsCalculatorTest {

    private final LearningMetricsCalculator calculator = new LearningMetricsCalculator();

    private static NegotiationRecordRow row(
            String supplier,
            String sku,
            String type,
            double reference,
            double floor,
            Double price,
            String outcome,
            Integer day) {
        return new NegotiationRecordRow(
                supplier,
                sku,
                type,
                null,
                reference,
                floor,
                reference,
                price,
                outcome,
                null,
                day == null ? null : day - 1,
                day,
                3,
                List.of(),
                List.of(),
                List.of());
    }

    private static NegotiationRecordRow agree(
            String supplier, String sku, double reference, double floor, double price, int day) {
        return row(supplier, sku, "good", reference, floor, price, "Agreement", day);
    }

    private static NegotiationRecordRow disagree(
            String supplier, String sku, double reference, double floor, int day) {
        return row(supplier, sku, "good", reference, floor, null, "Disagreement", day);
    }

    /** 主 fixture：一个诚信 pair 的三次成交（π = 0.8 / 0.6 / 0.9）。 */
    private static List<NegotiationRecordRow> mainPair() {
        return new ArrayList<>(
                List.of(
                        agree("S1", "SKU1", 100.0, 50.0, 90.0, 1),
                        agree("S1", "SKU1", 100.0, 50.0, 80.0, 2),
                        agree("S1", "SKU1", 100.0, 50.0, 95.0, 3)));
    }

    @Test
    void computesAnchorRegretAndNewLowForOneRepeatPair() {
        LearningMetrics metrics = calculator.calculate(mainPair());

        assertThat(metrics.repeatPairs()).isEqualTo(1);
        assertThat(metrics.reorders()).isEqualTo(2);
        assertThat(metrics.anchorRegret()).isCloseTo(0.15, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void poolsReordersAcrossPairs() {
        List<NegotiationRecordRow> rows = mainPair();
        // 第二个诚信 pair：Δ=100，两次同价成交 → π=0.8/0.8，regret 0、未创新低
        rows.add(agree("S2", "SKU2", 200.0, 100.0, 180.0, 1));
        rows.add(agree("S2", "SKU2", 200.0, 100.0, 180.0, 5));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.repeatPairs()).isEqualTo(2);
        assertThat(metrics.reorders()).isEqualTo(3);
        assertThat(metrics.anchorRegret()).isCloseTo(0.1, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(1.0 / 3.0, within(1e-9));
    }

    @Test
    void clipsPositionsOutsideBargainingRange() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        // π 会被截断：p=120 → (120−50)/50 = 1.4 → 1.0；p=40 → −0.2 → 0.0
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 120.0, 1));
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 40.0, 2));

        LearningMetrics metrics = calculator.calculate(rows);

        // π = 1.0 → 0.0：regret = max(0, 0−1) = 0；NewLow = 1[0.0 < 1.0−0.01] = 1
        assertThat(metrics.anchorRegret()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void appliesTheFourPaperFilters() {
        List<NegotiationRecordRow> rows = mainPair();
        // 过滤 1：欺诈 pair（即使重复成交也不计入）
        rows.add(row("BAD", "SKU9", "bad", 100.0, 50.0, 90.0, "Agreement", 1));
        rows.add(row("BAD", "SKU9", "bad", 100.0, 50.0, 95.0, "Agreement", 2));
        // 过滤 2：无结束日的成交
        rows.add(row("S3", "SKU3", "good", 100.0, 50.0, 90.0, "Agreement", null));
        rows.add(row("S3", "SKU3", "good", 100.0, 50.0, 85.0, "Agreement", null));
        // 过滤 3：Δ ≤ 0（参考价 == 成本底价）
        rows.add(agree("S4", "SKU4", 50.0, 50.0, 50.0, 1));
        rows.add(agree("S4", "SKU4", 50.0, 50.0, 50.0, 2));
        // 过滤 4：只成交一次的 pair
        rows.add(agree("S5", "SKU5", 100.0, 50.0, 70.0, 1));
        // 未成交的会话不进入 anchoring
        rows.add(disagree("S1", "SKU1", 100.0, 50.0, 4));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.repeatPairs()).isEqualTo(1);
        assertThat(metrics.reorders()).isEqualTo(2);
        assertThat(metrics.anchorRegret()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void dropsRowsWithMissingPriceFields() {
        // reference_price 缺失（读取器对类型不符会降级为 null）→ 该行不参与 anchoring
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(
                new NegotiationRecordRow(
                        "S1", "SKU1", "good", null, null, 100.0, null, 90.0, "Agreement", null, 0, 1,
                        3, List.of(), List.of(), List.of()));
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 80.0, 2));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.repeatPairs()).isZero();
        assertThat(metrics.anchorRegret()).isNull();
    }

    @Test
    void noRepeatPairYieldsAllNullLearningMetrics() {
        LearningMetrics metrics =
                calculator.calculate(List.of(agree("S1", "SKU1", 100.0, 50.0, 90.0, 1)));

        assertThat(metrics.repeatPairs()).isZero();
        assertThat(metrics.reorders()).isZero();
        assertThat(metrics.anchorRegret()).isNull();
        assertThat(metrics.newLow()).isNull();
        assertThat(metrics.anchorRatio()).isNull();
        assertThat(metrics.zEpisode()).isNull();
    }

    @Test
    void emptyInputYieldsAllNullLearningMetrics() {
        LearningMetrics metrics = calculator.calculate(List.of());

        assertThat(metrics.repeatPairs()).isZero();
        assertThat(metrics.reorders()).isZero();
        assertThat(metrics.anchorRegret()).isNull();
        assertThat(metrics.seHalfLift()).isNull();
        assertThat(metrics.seTrend()).isNull();
        assertThat(metrics.fraudHalfLift()).isNull();
    }

    @Test
    void sortsReordersByConclusionDayNotInputOrder() {
        // 输入顺序故意与日期相反：按 day 升序后 π = 0.8 → 0.6，regret = 0、NewLow = 1
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 80.0, 9));
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 90.0, 2));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.anchorRegret()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void permutationNullConvergesToAnalyticMean() {
        LearningMetrics metrics = calculator.calculate(mainPair());

        // 6 个置换的解析均值：regret = 1.6/6/2 = 0.13333…，容差取 B=400 抽样均值的 ≈4σ
        assertThat(metrics.nullMean()).isCloseTo(2.0 / 15.0, within(0.02));
        assertThat(metrics.nullStd()).isGreaterThan(0.0);
        // 观测 regret 0.15 高于零分布均值 → AnchorRatio > 1（比自身价格随机排列更贵）
        assertThat(metrics.anchorRatio()).isCloseTo(0.15 / (2.0 / 15.0), within(0.2));
        assertThat(metrics.anchorRatio()).isGreaterThan(1.0);
    }

    @Test
    void ratioAndZAreInternallyConsistentWithTheNullMoments() {
        LearningMetrics metrics = calculator.calculate(mainPair());

        assertThat(metrics.anchorRatio())
                .isCloseTo(metrics.anchorRegret() / metrics.nullMean(), within(1e-12));
        assertThat(metrics.zEpisode())
                .isCloseTo(
                        (metrics.nullMean() - metrics.anchorRegret()) / metrics.nullStd(),
                        within(1e-12));
        // 观测 regret 高于零分布均值 → z 为负（论文：负 z = 比自身随机排列更贵）
        assertThat(metrics.zEpisode()).isNegative();
        // NewLow 的零分布矩不在 LearningMetrics 契约内（spec §4.2），故只断言可观测的方向：
        // 观测 NewLow 0.5 高于解析零均值 2.5/6 = 0.41667 → 正（优于自身随机排列）
        assertThat(metrics.newLowRatioZ()).isPositive();
    }

    @Test
    void sameSeedIsBitwiseReproducible() {
        LearningMetrics first = new LearningMetricsCalculator(20260101L, 400).calculate(mainPair());
        LearningMetrics second = new LearningMetricsCalculator(20260101L, 400).calculate(mainPair());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentSeedStillConvergesToTheSameAnalyticNull() {
        LearningMetrics other = new LearningMetricsCalculator(999L, 400).calculate(mainPair());

        assertThat(other.nullMean()).isCloseTo(2.0 / 15.0, within(0.02));
        assertThat(other.anchorRegret()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void degenerateNullYieldsNullRatioAndZ() {
        // 两笔都以成本底价成交 → π 全为 0 → 观测与零分布都退化（μ=0、σ=0）
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 50.0, 1));
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 50.0, 2));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.anchorRegret()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.nullMean()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.nullStd()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.anchorRatio()).isNull();
        assertThat(metrics.zEpisode()).isNull();
        assertThat(metrics.newLowRatioZ()).isNull();
    }
}
