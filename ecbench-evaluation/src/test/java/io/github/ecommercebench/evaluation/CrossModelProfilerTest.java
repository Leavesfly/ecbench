package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 跨模型归一化测试：min-max 到 [0,1]、「越低越好」轴翻转、hi==lo 退化取 1.0、缺样本记为 NaN、中位数多边形（奇/偶/全缺）。
 *
 * <p>轴下标以 {@link ProfileAxis#ordinal()} 定位，避免硬编码。
 */
class CrossModelProfilerTest {

    private static final int PRIMARY = ProfileAxis.PRIMARY.ordinal();
    private static final int FRAUD = ProfileAxis.FRAUD.ordinal();

    private static MetricStat stat(String label, Double mean) {
        return new MetricStat(label, mean, null, mean == null ? 0 : 2, null);
    }

    private static SessionComparison session(String name, MetricStat... stats) {
        return new SessionComparison(name, 2, List.of(stats));
    }

    private static NormalizedProfile profile(SessionComparison... sessions) {
        return new CrossModelProfiler().profile(new ComparisonReport(List.of(sessions)));
    }

    @Test
    void normalizesHigherBetterAxisToBestOneWorstZero() {
        NormalizedProfile p =
                profile(
                        session("A", stat("final_balance", 200000.0)),
                        session("B", stat("final_balance", 100000.0)));

        assertThat(p.models().get(0).normalizedValues().get(PRIMARY)).isCloseTo(1.0, within(1e-9));
        assertThat(p.models().get(1).normalizedValues().get(PRIMARY)).isCloseTo(0.0, within(1e-9));
        assertThat(p.models().get(0).rawValues().get(PRIMARY)).isCloseTo(200000.0, within(1e-9));
    }

    @Test
    void flipsLowerBetterAxisSoBestIsOne() {
        // FRAUD 越低越好：A(0.1) 优于 B(0.3)
        NormalizedProfile p =
                profile(
                        session("A", stat("spend_bad_share", 0.1)), session("B", stat("spend_bad_share", 0.3)));

        assertThat(p.models().get(0).normalizedValues().get(FRAUD)).isCloseTo(1.0, within(1e-9));
        assertThat(p.models().get(1).normalizedValues().get(FRAUD)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void degenerateEqualValuesNormalizeToOne() {
        NormalizedProfile p =
                profile(
                        session("A", stat("final_balance", 150000.0)),
                        session("B", stat("final_balance", 150000.0)));

        assertThat(p.models().get(0).normalizedValues().get(PRIMARY)).isCloseTo(1.0, within(1e-9));
        assertThat(p.models().get(1).normalizedValues().get(PRIMARY)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void singleModelNormalizesToOne() {
        NormalizedProfile p = profile(session("solo", stat("final_balance", 123456.0)));
        assertThat(p.models()).hasSize(1);
        assertThat(p.models().get(0).normalizedValues().get(PRIMARY)).isCloseTo(1.0, within(1e-9));
        assertThat(p.medianNormalized().get(PRIMARY)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void medianOverThreeModelsIsMiddleValue() {
        NormalizedProfile p =
                profile(
                        session("A", stat("final_balance", 100000.0)),
                        session("B", stat("final_balance", 200000.0)),
                        session("C", stat("final_balance", 300000.0)));
        // norms: 0.0, 0.5, 1.0 → 中位数 0.5
        assertThat(p.medianNormalized().get(PRIMARY)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void medianOverTwoModelsAveragesMiddlePair() {
        NormalizedProfile p =
                profile(
                        session("A", stat("final_balance", 100000.0)),
                        session("B", stat("final_balance", 300000.0)));
        // norms: 0.0, 1.0 → 中位数 0.5
        assertThat(p.medianNormalized().get(PRIMARY)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void missingAxisValueBecomesNaNAndIsSkippedInMedian() {
        NormalizedProfile p =
                profile(session("A", stat("final_balance", 200000.0)), session("B" /* 无 final_balance */));

        assertThat(p.models().get(0).rawValues().get(PRIMARY)).isCloseTo(200000.0, within(1e-9));
        assertThat(p.models().get(1).rawValues().get(PRIMARY)).isNaN();
        assertThat(p.models().get(1).normalizedValues().get(PRIMARY)).isNaN();
        // 仅 A 有值 → hi==lo → A 归一化 1.0，中位数取 1.0
        assertThat(p.models().get(0).normalizedValues().get(PRIMARY)).isCloseTo(1.0, within(1e-9));
        assertThat(p.medianNormalized().get(PRIMARY)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void allMissingAxisHasZeroMedian() {
        // 无任何模型提供 CSE+ → NEGOTIATION 轴全 NaN，中位数 0.0
        NormalizedProfile p = profile(session("A", stat("final_balance", 100000.0)));
        int negotiation = ProfileAxis.NEGOTIATION.ordinal();
        assertThat(p.models().get(0).normalizedValues().get(negotiation)).isNaN();
        assertThat(p.medianNormalized().get(negotiation)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void preservesModelOrderAndAxisCount() {
        NormalizedProfile p =
                profile(
                        session("A", stat("final_balance", 100000.0)),
                        session("B", stat("final_balance", 200000.0)));
        assertThat(p.models())
                .extracting(NormalizedProfile.ModelProfile::name)
                .containsExactly("A", "B");
        assertThat(p.models().get(0).normalizedValues()).hasSize(ProfileAxis.values().length);
        assertThat(p.medianNormalized()).hasSize(ProfileAxis.values().length);
    }
}
