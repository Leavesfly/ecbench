package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 六维度口径测试：轴序即论文 §4.3 顺序、排名指标方向、派生比值的分母保护、诊断指标 path。 */
class DimensionAxisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode analysis(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void axisOrderFollowsPaperSectionOrder() {
        assertThat(DimensionAxis.values())
                .containsExactly(
                        DimensionAxis.NEGOTIATION,
                        DimensionAxis.FRAUD,
                        DimensionAxis.SOLVENCY,
                        DimensionAxis.EFFICIENCY,
                        DimensionAxis.EXECUTION,
                        DimensionAxis.LEARNING);
        assertThat(Arrays.stream(DimensionAxis.values()).map(DimensionAxis::key).toList())
                .containsExactly(
                        "negotiation", "fraud", "solvency", "efficiency", "execution", "learning");
    }

    @Test
    void directionsMatchPaper() {
        assertThat(DimensionAxis.NEGOTIATION.lowerIsBetter()).isFalse();
        assertThat(DimensionAxis.EFFICIENCY.lowerIsBetter()).isFalse();
        assertThat(DimensionAxis.FRAUD.lowerIsBetter()).isTrue();
        assertThat(DimensionAxis.SOLVENCY.lowerIsBetter()).isTrue();
        assertThat(DimensionAxis.EXECUTION.lowerIsBetter()).isTrue();
        assertThat(DimensionAxis.LEARNING.lowerIsBetter()).isTrue();
    }

    @Test
    void negotiationRankMetricIsCsePlus() throws Exception {
        JsonNode node = analysis("{\"negotiation_quality\":{\"CSE+\":0.5005}}");

        assertThat(DimensionAxis.NEGOTIATION.raw(node)).isCloseTo(0.5005, within(1e-9));
        assertThat(DimensionAxis.NEGOTIATION.rankLabel()).isEqualTo("CSE+");
    }

    @Test
    void solvencyRankMetricIsDrawdownOverPeakAssets() throws Exception {
        JsonNode node =
                analysis(
                        "{\"profitability\":{\"peak_drawdown\":20000.0,\"peak_total_assets\":200000.0}}");

        assertThat(DimensionAxis.SOLVENCY.raw(node)).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void zeroDenominatorYieldsNullForDerivedRankMetrics() throws Exception {
        JsonNode zeroAssets =
                analysis("{\"profitability\":{\"peak_drawdown\":1000.0,\"peak_total_assets\":0.0}}");
        JsonNode zeroCalls =
                analysis(
                        "{\"profitability\":{\"final_balance\":150000.0,\"initial_balance\":100000.0},"
                                + "\"operational_efficiency\":{\"total_tool_calls\":0}}");

        assertThat(DimensionAxis.SOLVENCY.raw(zeroAssets)).isNull();
        assertThat(DimensionAxis.EFFICIENCY.raw(zeroCalls)).isNull();
    }

    @Test
    void efficiencyRankMetricIsProfitPerToolCall() throws Exception {
        JsonNode node =
                analysis(
                        "{\"profitability\":{\"final_balance\":150000.0,\"initial_balance\":100000.0},"
                                + "\"operational_efficiency\":{\"total_tool_calls\":500}}");

        assertThat(DimensionAxis.EFFICIENCY.raw(node)).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void learningAxisHasNoAnalysisValueAndNoDiagnostics() throws Exception {
        JsonNode node = analysis("{\"negotiation_quality\":{\"learning_speed\":{\"se_half_lift\":0.05}}}");

        assertThat(DimensionAxis.LEARNING.raw(node)).isNull();
        assertThat(DimensionAxis.LEARNING.diagnostics()).isEmpty();
        assertThat(DimensionAxis.LEARNING.rankLabel()).isEqualTo("AnchorRatio");
    }

    @Test
    void diagnosticsExposePaperLabelsWithPaths() {
        List<DimensionAxis.Diagnostic> fraud = DimensionAxis.FRAUD.diagnostics();

        assertThat(fraud).isNotEmpty();
        assertThat(fraud).anySatisfy(d -> {
            assertThat(d.label()).isEqualTo("spend_on_bad");
            assertThat(d.labelZh()).isEqualTo("欺诈供应商支出");
            assertThat(d.path()).containsExactly("fraud_identification", "spend_on_bad_supplier");
        });
        assertThat(DimensionAxis.NEGOTIATION.diagnostics())
                .extracting(DimensionAxis.Diagnostic::label)
                .contains("SE+", "%Oracle", "AGR+", "avg_rounds", "money_saved");
    }

    @Test
    void missingFieldYieldsNullNotZero() throws Exception {
        assertThat(DimensionAxis.at(analysis("{}"), "profitability", "final_balance")).isNull();
        assertThat(DimensionAxis.at(analysis("{\"profitability\":{}}"), "profitability", "final_balance"))
                .isNull();
        assertThat(DimensionAxis.at(analysis("{\"profitability\":{\"bankrupt\":true}}"),
                "profitability", "bankrupt")).isEqualTo(1.0);
    }
}
