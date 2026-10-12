package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ecommercebench.evaluation.io.NegotiationRecordJsonlReader;
import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.SessionLearning;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话级学习指标聚合测试：run 发现、Stouffer 合并、半年比较跨 run 均值、缺产物降级为 null。 */
class LearningMetricsServiceTest {

    /** 与 LearningMetricsCalculatorTest 主 fixture 相同的 jsonl 行（π = 0.8 / 0.6 / 0.9）。 */
    private static final String PAIR_LINES =
            "{\"supplier\":\"S1\",\"sku_id\":\"SKU1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":90.0,\"outcome\":\"Agreement\",\"day_concluded\":1}\n"
                    + "{\"supplier\":\"S1\",\"sku_id\":\"SKU1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":80.0,\"outcome\":\"Agreement\",\"day_concluded\":2}\n"
                    + "{\"supplier\":\"S1\",\"sku_id\":\"SKU1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":95.0,\"outcome\":\"Agreement\",\"day_concluded\":3}\n";

    /** 四个诚信已结束会话（各不同 SKU），s_z = 0.2 / 0.5 / 0 / 0.8 → h_SE=0.05、b_SE=0.13。 */
    private static final String HALF_YEAR_LINES =
            "{\"supplier\":\"H1\",\"sku_id\":\"K1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":90.0,\"outcome\":\"Agreement\",\"day_concluded\":1}\n"
                    + "{\"supplier\":\"H2\",\"sku_id\":\"K2\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":75.0,\"outcome\":\"Agreement\",\"day_concluded\":2}\n"
                    + "{\"supplier\":\"H3\",\"sku_id\":\"K3\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"outcome\":\"Disagreement\",\"day_concluded\":3}\n"
                    + "{\"supplier\":\"H4\",\"sku_id\":\"K4\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":60.0,\"outcome\":\"Agreement\",\"day_concluded\":4}\n";

    private final LearningMetricsService service = new LearningMetricsService();

    private static void writeRecords(Path session, int runIndex, String lines) throws Exception {
        Path metrics = session.resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(metrics.resolve("run_" + runIndex + "_negotiation_records.jsonl"), lines);
    }

    @Test
    void aggregatesTwoIdenticalRunsWithStoufferRule(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_m");
        writeRecords(session, 0, PAIR_LINES);
        writeRecords(session, 1, PAIR_LINES);

        SessionLearning learning = service.forSession(session);
        LearningMetrics single =
                new LearningMetricsCalculator()
                        .calculate(
                                new NegotiationRecordJsonlReader()
                                        .read(session.resolve("metrics/run_0_negotiation_records.jsonl")));

        assertThat(learning).isNotNull();
        assertThat(learning.episodes()).isEqualTo(2);
        assertThat(learning.repeatPairs()).isEqualTo(2);
        assertThat(learning.reorders()).isEqualTo(4);
        assertThat(learning.anchorRegret()).isCloseTo(0.15, within(1e-9));
        // 两个 run 完全相同 → 比率均值等于单 run 比率；z 按 (1/√k)Σz_r = z·√2
        assertThat(learning.anchorRatio()).isCloseTo(single.anchorRatio(), within(1e-9));
        assertThat(learning.zAnchor()).isCloseTo(single.zEpisode() * Math.sqrt(2.0), within(1e-9));
    }

    @Test
    void runsWithoutRepeatPairsDoNotCountTowardEpisodes(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_n");
        writeRecords(session, 0, PAIR_LINES);
        writeRecords(
                session,
                1,
                "{\"supplier\":\"S9\",\"sku_id\":\"K9\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"final_price\":90.0,\"outcome\":\"Agreement\","
                        + "\"day_concluded\":1}\n");

        SessionLearning learning = service.forSession(session);

        assertThat(learning.episodes()).isEqualTo(1);
        assertThat(learning.repeatPairs()).isEqualTo(1);
        assertThat(learning.reorders()).isEqualTo(2);
        assertThat(learning.zAnchor()).isCloseTo(service.forRun(session, 0).zEpisode(), within(1e-9));
    }

    @Test
    void sessionWithoutArtifactYieldsNull(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_legacy");
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(session.resolve("metrics/run_0_analysis.json"), "{}");

        assertThat(service.forSession(session)).isNull();
        assertThat(service.forRun(session, 0)).isNull();
    }

    @Test
    void emptyArtifactYieldsZeroCountersAndNullValues(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_empty");
        writeRecords(session, 0, "");

        SessionLearning learning = service.forSession(session);

        assertThat(learning).isNotNull();
        assertThat(learning.episodes()).isZero();
        assertThat(learning.anchorRatio()).isNull();
        assertThat(learning.zAnchor()).isNull();
        assertThat(learning.repeatPairs()).isZero();
        assertThat(learning.reorders()).isZero();
    }

    @Test
    void aggregatesHalfYearMetricsAcrossRuns(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_h");
        writeRecords(session, 0, HALF_YEAR_LINES);
        writeRecords(session, 1, HALF_YEAR_LINES);

        SessionLearning learning = service.forSession(session);

        assertThat(learning.seHalfLift()).isCloseTo(0.05, within(1e-9));
        assertThat(learning.seTrend()).isCloseTo(0.13, within(1e-9));
        assertThat(learning.episodes()).isZero();
        assertThat(learning.anchorRatio()).isNull();
    }

    @Test
    void discoversRunsOutOfOrderAndIgnoresOtherArtifacts(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_order");
        writeRecords(session, 10, PAIR_LINES);
        writeRecords(session, 2, PAIR_LINES);
        Files.writeString(session.resolve("metrics/run_2_analysis.json"), "{}");
        Files.writeString(session.resolve("metrics/run_2_negotiation_metrics.json"), "{}");

        SessionLearning learning = service.forSession(session);

        // 两个 run 都有重复 pair → k=2；只认 *_negotiation_records.jsonl，不误读 analysis/negotiation_metrics
        assertThat(learning.episodes()).isEqualTo(2);
        assertThat(learning.repeatPairs()).isEqualTo(2);
        assertThat(service.forRun(session, 2)).isNotNull();
        assertThat(service.forRun(session, 10)).isNotNull();
        assertThat(service.forRun(session, 3)).isNull();
    }

    @Test
    void legacyFlatLayoutIsAlsoDiscovered(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_flat");
        Files.createDirectories(session);
        Files.writeString(session.resolve("run_0_negotiation_records.jsonl"), PAIR_LINES);

        SessionLearning learning = service.forSession(session);

        assertThat(learning).isNotNull();
        assertThat(learning.episodes()).isEqualTo(1);
        assertThat(learning.anchorRegret()).isCloseTo(0.15, within(1e-9));
    }
}
