package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.RunComparator;
import io.github.ecommercebench.evaluation.model.ComparisonReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 维度服务测试：主评分、六维度值与排名、rank crossings、嵌套诊断块、空输入与缺字段的降级。 */
class DimensionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个字段齐备的 analysis.json；cse/badShare/toolCalls 可调以制造排名差异。 */
    private static String analysis(
            double finalScore, double cse, double badShare, double drawdown, double peakAssets,
            double finalBalance, int toolCalls, double controllable) {
        return "{"
                + "\"reward\":{\"final_score\":" + finalScore + ",\"termination_reason\":\"env_terminated\","
                + "\"termination_detail\":\"max_turns_reached\"},"
                + "\"profitability\":{\"final_balance\":" + finalBalance + ",\"initial_balance\":100000.0,"
                + "\"peak_drawdown\":" + drawdown + ",\"peak_total_assets\":" + peakAssets + ","
                + "\"bankrupt\":false,\"final_day\":365,\"stores_opened\":3,\"store_reopens\":0},"
                + "\"negotiation_quality\":{\"SE+\":" + cse + ",\"CSE+\":" + cse + ",\"%Oracle\":" + cse + ","
                + "\"AGR+\":1.0,\"avg_rounds_to_deal\":3.5,\"total_money_saved_vs_initial\":0.1,"
                + "\"learning_speed\":{}},"
                + "\"fraud_identification\":{\"bad_supplier_order_share\":0.0,"
                + "\"spend_on_bad_supplier\":" + (badShare * 1000)
                + ",\"spend_on_bad_supplier_share\":" + badShare + ","
                + "\"vip_fee_paid_count\":0,\"vip_fee_paid_amount\":0.0,"
                + "\"spend_by_fraud_type\":{\"vip_fee\":{\"orders\":1,\"units\":2,\"spend\":300.0},"
                + "\"qty_bait\":{\"orders\":0,\"units\":0,\"spend\":0.0}},"
                + "\"spend_by_good_personality\":{\"Friendly\":591.9}},"
                + "\"supplier_engagement\":{\"contacted\":{\"distinct_total\":5,\"distinct_good\":4,"
                + "\"distinct_bad\":1},\"ordered\":{\"distinct_total\":2,\"distinct_good\":2,\"distinct_bad\":0},"
                + "\"roster_totals\":{\"distinct_total\":576,\"distinct_good\":424,\"distinct_bad\":152}},"
                + "\"fulfilment_quality\":{\"orders_sold\":10,\"orders_shipped\":9,\"orders_cancelled\":1,"
                + "\"on_time_ship_rate\":0.9,\"units_sold\":20,\"units_returned\":2,"
                + "\"realized_return_rate\":0.1,\"ship_speed_counts\":{\"fast\":3,\"standard\":5,\"slow\":1}},"
                + "\"return_management\":{\"units_sold\":20,\"units_returned\":2,\"realized_return_rate\":0.1,"
                + "\"refund_loss_total\":120.0,\"shipping_loss_on_returns\":30.0,\"units_shipped\":20,"
                + "\"exp_return_rate\":0.15,\"exp_return_rate_natural\":0.05,"
                + "\"exp_return_rate_from_pricing\":0.06,\"exp_return_rate_from_ship_speed\":0.03,"
                + "\"exp_return_rate_from_defective_fraud\":0.01,\"controllable_return_rate\":" + controllable + ","
                + "\"ship_speed_counts\":{\"fast\":3,\"standard\":5,\"slow\":1}},"
                + "\"operational_efficiency\":{\"total_tool_calls\":" + toolCalls + ",\"turns\":" + toolCalls + ","
                + "\"profit_per_tool_call\":10.0,\"calls_by_band\":{\"waiting\":2,\"shipping\":1,\"listing\":0,"
                + "\"withdraw\":0,\"bargaining\":6,\"state_polling\":5,\"memory\":0,\"remainder\":16},"
                + "\"calls_by_band_share\":{\"waiting\":6.67,\"shipping\":3.33,\"listing\":0.0,\"withdraw\":0.0,"
                + "\"bargaining\":20.0,\"state_polling\":16.67,\"memory\":0.0,\"remainder\":53.33},"
                + "\"memory_calls\":0,\"memory_share\":0.0,\"context_evictions\":0,\"context_tokens_freed\":0}"
                + "}";
    }

    private static Path session(Path root, String name, String json) throws Exception {
        Path metrics = root.resolve(name).resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(metrics.resolve("run_0_analysis.json"), json);
        return root.resolve(name);
    }

    private static ObjectNode compare(List<Path> dirs) {
        ComparisonReport report = new RunComparator().compare(dirs);
        return new DimensionService(MAPPER).node(dirs, report);
    }

    @Test
    void primaryScoreIsTheAssetMultiplierWithRanks(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));
        Path b = session(root, "2026_b", analysis(0.9, 0.4, 0.3, 30000, 180000, 90000, 400, 0.20));

        JsonNode primary = compare(List.of(a, b)).path("primaryScore");

        assertThat(primary.path("labelZh").asText()).isEqualTo("年末总资产倍数");
        assertThat(primary.path("unit").asText()).isEqualTo("×");
        assertThat(primary.path("rows")).hasSize(2);
        assertThat(primary.path("rows").get(0).path("name").asText()).isEqualTo("2026_a");
        assertThat(primary.path("rows").get(0).path("value").asDouble()).isCloseTo(1.5, within(1e-9));
        assertThat(primary.path("rows").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(primary.path("rows").get(1).path("rank").asInt()).isEqualTo(2);
        // spec §5.4：主评分卡还要带年末总资产均值、破产 run 数与终止原因
        assertThat(primary.path("rows").get(0).path("finalBalance").asDouble())
                .isCloseTo(150000.0, within(1e-9));
        assertThat(primary.path("rows").get(0).path("bankruptRuns").asInt()).isZero();
        assertThat(primary.path("rows").get(0).path("termination").asText())
                .isEqualTo("env_terminated");
    }

    @Test
    void sixDimensionsAreEmittedInPaperOrder(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode dimensions = compare(List.of(a)).path("dimensions");

        assertThat(dimensions).hasSize(6);
        assertThat(dimensions.get(0).path("key").asText()).isEqualTo("negotiation");
        assertThat(dimensions.get(0).path("labelZh").asText()).isEqualTo("谈判质量");
        assertThat(dimensions.get(0).path("paperRef").asText()).isEqualTo("§4.3.1 / §E.1");
        assertThat(dimensions.get(0).path("rankMetric").path("label").asText()).isEqualTo("CSE+");
        assertThat(dimensions.get(0).path("rankMetric").path("direction").asText()).isEqualTo("higher");
        assertThat(dimensions.get(5).path("key").asText()).isEqualTo("learning");
    }

    @Test
    void lowerIsBetterDimensionsRankSmallestValueFirst(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.30, 20000, 250000, 150000, 500, 0.30));
        Path b = session(root, "2026_b", analysis(0.9, 0.4, 0.05, 30000, 180000, 90000, 400, 0.02));

        JsonNode dimensions = compare(List.of(a, b)).path("dimensions");
        JsonNode fraud = dimensions.get(1);

        assertThat(fraud.path("key").asText()).isEqualTo("fraud");
        assertThat(fraud.path("rankMetric").path("direction").asText()).isEqualTo("lower");
        // b 的欺诈支出占比更低 → rank 1
        assertThat(fraud.path("rows").get(0).path("name").asText()).isEqualTo("2026_b");
        assertThat(fraud.path("rows").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(fraud.path("rows").get(1).path("name").asText()).isEqualTo("2026_a");
        assertThat(fraud.path("rows").get(1).path("rank").asInt()).isEqualTo(2);
    }

    @Test
    void tiedValuesShareTheSameCompetitionRank(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.0, 0.5, 0.1, 20000, 200000, 100000, 500, 0.10));
        Path b = session(root, "2026_b", analysis(1.0, 0.5, 0.1, 20000, 200000, 100000, 500, 0.10));
        Path c = session(root, "2026_c", analysis(0.5, 0.2, 0.4, 40000, 200000, 50000, 500, 0.40));

        JsonNode negotiation = compare(List.of(a, b, c)).path("dimensions").get(0);

        assertThat(negotiation.path("rows").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(negotiation.path("rows").get(1).path("rank").asInt()).isEqualTo(1);
        assertThat(negotiation.path("rows").get(2).path("rank").asInt()).isEqualTo(3);
    }

    @Test
    void rankCrossingsCarryPrimaryAndSixDimensionRanks(@TempDir Path root) throws Exception {
        // a 主评分第一但欺诈维度最差 → 出现排名交叉
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.30, 20000, 250000, 150000, 500, 0.08));
        Path b = session(root, "2026_b", analysis(0.9, 0.4, 0.05, 30000, 180000, 90000, 400, 0.20));

        JsonNode crossings = compare(List.of(a, b)).path("rankCrossings");

        assertThat(crossings).hasSize(2);
        JsonNode first = crossings.get(0);
        assertThat(first.path("name").asText()).isEqualTo("2026_a");
        assertThat(first.path("primary").asInt()).isEqualTo(1);
        assertThat(first.path("ranks").path("negotiation").asInt()).isEqualTo(1);
        assertThat(first.path("ranks").path("fraud").asInt()).isEqualTo(2);
        assertThat(first.path("ranks").path("learning").isNull()).isTrue();
    }

    @Test
    void diagnosticsCarryChineseAnnotationsAndMeanValues(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode negotiation = compare(List.of(a)).path("dimensions").get(0);
        JsonNode diagnostics = negotiation.path("diagnostics");

        assertThat(diagnostics).isNotEmpty();
        JsonNode sePlus = diagnostics.get(0);
        assertThat(sePlus.path("label").asText()).isEqualTo("SE+");
        assertThat(sePlus.path("labelZh").asText()).isEqualTo("剩余效率");
        assertThat(sePlus.path("values").path("2026_a").asDouble()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void nestedBlocksExposeBandsFraudTypesAndReturnLayers(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode dimensions = compare(List.of(a)).path("dimensions");
        JsonNode efficiencyBlocks = dimensions.get(3).path("blocks");
        JsonNode fraudBlocks = dimensions.get(1).path("blocks");
        JsonNode executionBlocks = dimensions.get(4).path("blocks");

        assertThat(efficiencyBlocks.path("callsByBand").path("bargaining").path("count").asInt())
                .isEqualTo(6);
        assertThat(efficiencyBlocks.path("callsByBand").path("bargaining").path("share").asDouble())
                .isCloseTo(20.0, within(1e-9));
        assertThat(fraudBlocks.path("spendByFraudType").path("vip_fee").path("spend").asDouble())
                .isCloseTo(300.0, within(1e-9));
        assertThat(fraudBlocks.path("spendByFraudType").path("qty_bait").path("orders").asInt())
                .isZero();
        assertThat(executionBlocks.path("returnLayers").path("exp_return_rate_from_pricing").asDouble())
                .isCloseTo(0.06, within(1e-9));
        assertThat(executionBlocks.path("shipSpeed").path("standard").asInt()).isEqualTo(5);
    }

    @Test
    void averagesAcrossRunsOfTheSameSession(@TempDir Path root) throws Exception {
        Path dir = root.resolve("2026_multi");
        Path metrics = dir.resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(
                metrics.resolve("run_0_analysis.json"),
                analysis(1.0, 0.4, 0.1, 10000, 100000, 100000, 400, 0.10));
        Files.writeString(
                metrics.resolve("run_1_analysis.json"),
                analysis(2.0, 0.8, 0.3, 30000, 100000, 200000, 600, 0.30));

        ObjectNode node = compare(List.of(dir));

        assertThat(node.path("primaryScore").path("rows").get(0).path("value").asDouble())
                .isCloseTo(1.5, within(1e-9));
        assertThat(node.path("dimensions").get(0).path("rows").get(0).path("value").asDouble())
                .isCloseTo(0.6, within(1e-9));
    }

    @Test
    void missingFieldsBecomeNullValuesAndNullRanks(@TempDir Path root) throws Exception {
        Path bare = root.resolve("2026_bare");
        Files.createDirectories(bare.resolve("metrics"));
        Files.writeString(bare.resolve("metrics/run_0_analysis.json"), "{\"reward\":{}}");

        ObjectNode node = compare(List.of(bare));

        assertThat(node.path("primaryScore").path("rows").get(0).path("value").isNull()).isTrue();
        assertThat(node.path("primaryScore").path("rows").get(0).path("rank").isNull()).isTrue();
        assertThat(node.path("dimensions").get(0).path("rows").get(0).path("value").isNull()).isTrue();
        assertThat(node.path("rankCrossings").get(0).path("ranks").path("negotiation").isNull()).isTrue();
    }

    @Test
    void emptyInputStillEmitsSixDimensionsAndEmptyRows(@TempDir Path root) {
        ObjectNode node = compare(List.of());

        assertThat(node.path("primaryScore").path("rows")).isEmpty();
        assertThat(node.path("dimensions")).hasSize(6);
        assertThat(node.path("dimensions").get(0).path("rows")).isEmpty();
        assertThat(node.path("rankCrossings")).isEmpty();
        assertThat(node.path("learning")).isEmpty();
    }

    @Test
    void learningSectionIsNullFilledForLegacySessions(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode learning = compare(List.of(a)).path("learning").path("2026_a");

        assertThat(learning.isMissingNode()).isFalse();
        assertThat(learning.path("anchorRatio").isNull()).isTrue();
        assertThat(learning.path("zAnchor").isNull()).isTrue();
        assertThat(learning.path("episodes").asInt()).isZero();
        assertThat(learning.path("repeatPairs").asInt()).isZero();
    }
}
