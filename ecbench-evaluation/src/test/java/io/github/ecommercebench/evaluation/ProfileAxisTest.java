package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 七轴定义测试：断言轴序、方向（论文 §3.7：欺诈/偿付/执行/学习为「越低越好」）、各轴从会话均值取值（含偿付/效率两个派生比值）， 以及缺样本或分母为 0 时返回 null。
 */
class ProfileAxisTest {

    private static MetricStat stat(String label, Double mean) {
        return new MetricStat(label, mean, null, mean == null ? 0 : 2, null);
    }

    private static SessionComparison fullSession() {
        return new SessionComparison(
                "modelA",
                2,
                List.of(
                        stat("final_balance", 150000.0),
                        stat("initial_balance", 100000.0),
                        stat("CSE+", 0.60),
                        stat("spend_bad_share", 0.25),
                        stat("peak_drawdown", 20000.0),
                        stat("peak_total_assets", 200000.0),
                        stat("tool_calls", 500.0),
                        stat("controllable_return_rate", 0.10),
                        stat("se_half_lift", 0.05)));
    }

    @Test
    void axisOrderIsPrimaryThenSixDimensions() {
        assertThat(ProfileAxis.values())
                .containsExactly(
                        ProfileAxis.PRIMARY,
                        ProfileAxis.NEGOTIATION,
                        ProfileAxis.FRAUD,
                        ProfileAxis.SOLVENCY,
                        ProfileAxis.EFFICIENCY,
                        ProfileAxis.EXECUTION,
                        ProfileAxis.LEARNING);
    }

    @Test
    void directionsMatchPaperSignFlipSet() {
        assertThat(ProfileAxis.PRIMARY.lowerIsBetter()).isFalse();
        assertThat(ProfileAxis.NEGOTIATION.lowerIsBetter()).isFalse();
        assertThat(ProfileAxis.EFFICIENCY.lowerIsBetter()).isFalse();
        assertThat(ProfileAxis.LEARNING.lowerIsBetter()).isFalse();
        assertThat(ProfileAxis.FRAUD.lowerIsBetter()).isTrue();
        assertThat(ProfileAxis.SOLVENCY.lowerIsBetter()).isTrue();
        assertThat(ProfileAxis.EXECUTION.lowerIsBetter()).isTrue();
    }

    @Test
    void computesAxisValuesIncludingDerivedRatios() {
        SessionComparison session = fullSession();
        assertThat(ProfileAxis.PRIMARY.value(session)).isCloseTo(150000.0, within(1e-9));
        assertThat(ProfileAxis.NEGOTIATION.value(session)).isCloseTo(0.60, within(1e-9));
        assertThat(ProfileAxis.FRAUD.value(session)).isCloseTo(0.25, within(1e-9));
        // solvency = peak_drawdown / peak_total_assets = 20000/200000
        assertThat(ProfileAxis.SOLVENCY.value(session)).isCloseTo(0.10, within(1e-9));
        // efficiency = (final - initial) / tool_calls = 50000/500
        assertThat(ProfileAxis.EFFICIENCY.value(session)).isCloseTo(100.0, within(1e-9));
        assertThat(ProfileAxis.EXECUTION.value(session)).isCloseTo(0.10, within(1e-9));
        assertThat(ProfileAxis.LEARNING.value(session)).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void missingMetricYieldsNull() {
        SessionComparison empty = new SessionComparison("modelB", 0, List.of());
        assertThat(ProfileAxis.PRIMARY.value(empty)).isNull();
        assertThat(ProfileAxis.SOLVENCY.value(empty)).isNull();
        assertThat(ProfileAxis.EFFICIENCY.value(empty)).isNull();
    }

    @Test
    void nullMeanMetricYieldsNull() {
        SessionComparison session = new SessionComparison("modelC", 1, List.of(stat("CSE+", null)));
        assertThat(ProfileAxis.NEGOTIATION.value(session)).isNull();
    }

    @Test
    void zeroDenominatorYieldsNullForDerivedAxes() {
        SessionComparison zeroPeak =
                new SessionComparison(
                        "modelD", 1, List.of(stat("peak_drawdown", 1000.0), stat("peak_total_assets", 0.0)));
        assertThat(ProfileAxis.SOLVENCY.value(zeroPeak)).isNull();

        SessionComparison zeroCalls =
                new SessionComparison(
                        "modelE",
                        1,
                        List.of(
                                stat("final_balance", 150000.0),
                                stat("initial_balance", 100000.0),
                                stat("tool_calls", 0.0)));
        assertThat(ProfileAxis.EFFICIENCY.value(zeroCalls)).isNull();
    }

    @Test
    void exposesStableKeyAndLabel() {
        assertThat(ProfileAxis.PRIMARY.key()).isEqualTo("primary");
        assertThat(ProfileAxis.SOLVENCY.key()).isEqualTo("solvency");
        assertThat(ProfileAxis.LEARNING.label()).isNotBlank();
    }
}
