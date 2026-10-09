package io.github.ecommercebench.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ecommercebench.evaluation.io.AnalysisJsonReader;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 多会话 analysis 比较器，端口 Python {@code compare_runs}（METRIC_DEFS + _mean_std + _extract_value）。
 *
 * <p>对每个会话加载其全部 {@code run_*_analysis.json}（organized metrics/ 或 legacy 平铺），按 {@link #METRIC_DEFS}
 * 的 JSON path 取值， 数值样本计算样本均值与样本标准差（n-1）；null/缺失/非数值被跳过而非计为 0；布尔按 1.0/0.0 计入（与 Python {@code
 * float(bool)} 一致）。 特殊的 "stores / reopens" 无 path，以文本 fallback 表示。
 */
public final class RunComparator {

    /**
     * 一个指标定义：展示标签 + analysis JSON path（空 path 表示特殊文本指标）。
     */
    public record MetricDef(String label, List<String> path) {
    }

    public static final List<MetricDef> METRIC_DEFS =
            List.of(
                    new MetricDef("final_balance", List.of("profitability", "final_balance")),
                    new MetricDef("bankrupt", List.of("profitability", "bankrupt")),
                    new MetricDef("final_day", List.of("profitability", "final_day")),
                    new MetricDef("peak_drawdown", List.of("profitability", "peak_drawdown")),
                    new MetricDef("stores / reopens", List.of()),
                    new MetricDef("SE+", List.of("negotiation_quality", "SE+")),
                    new MetricDef("CSE+", List.of("negotiation_quality", "CSE+")),
                    new MetricDef("%Oracle", List.of("negotiation_quality", "%Oracle")),
                    new MetricDef("AGR+", List.of("negotiation_quality", "AGR+")),
                    new MetricDef("avg_rounds", List.of("negotiation_quality", "avg_rounds_to_deal")),
                    new MetricDef(
                            "money_saved", List.of("negotiation_quality", "total_money_saved_vs_initial")),
                    new MetricDef(
                            "se_half_lift", List.of("negotiation_quality", "learning_speed", "se_half_lift")),
                    new MetricDef(
                            "agr_half_lift", List.of("negotiation_quality", "learning_speed", "agr_half_lift")),
                    new MetricDef(
                            "fraud_avoid_lift",
                            List.of("negotiation_quality", "learning_speed", "fraud_avoidance_lift")),
                    new MetricDef(
                            "time_to_zero_bad",
                            List.of("negotiation_quality", "learning_speed", "time_to_zero_bad")),
                    new MetricDef(
                            "rounds_improvement",
                            List.of("negotiation_quality", "learning_speed", "rounds_half_improvement")),
                    new MetricDef(
                            "bad_order_share", List.of("fraud_identification", "bad_supplier_order_share")),
                    new MetricDef("spend_on_bad", List.of("fraud_identification", "spend_on_bad_supplier")),
                    new MetricDef(
                            "spend_bad_share", List.of("fraud_identification", "spend_on_bad_supplier_share")),
                    new MetricDef("vip_fee_paid", List.of("fraud_identification", "vip_fee_paid_count")),
                    new MetricDef("on_time_ship", List.of("fulfilment_quality", "on_time_ship_rate")),
                    new MetricDef("return_rate", List.of("fulfilment_quality", "realized_return_rate")),
                    new MetricDef("tool_calls", List.of("operational_efficiency", "total_tool_calls")),
                    new MetricDef(
                            "profit_per_tool_call", List.of("operational_efficiency", "profit_per_tool_call")),
                    new MetricDef(
                            "context_evictions", List.of("operational_efficiency", "context_evictions")),
                    new MetricDef("memory_calls", List.of("operational_efficiency", "memory_calls")),
                    new MetricDef("peak_total_assets", List.of("profitability", "peak_total_assets")),
                    new MetricDef("initial_balance", List.of("profitability", "initial_balance")),
                    new MetricDef(
                            "controllable_return_rate",
                            List.of("return_management", "controllable_return_rate")));

    private final AnalysisJsonReader reader;
    private final RunLayoutResolver resolver;

    public RunComparator() {
        this(new AnalysisJsonReader(), new RunLayoutResolver());
    }

    public RunComparator(AnalysisJsonReader reader, RunLayoutResolver resolver) {
        this.reader = reader;
        this.resolver = resolver;
    }

    public ComparisonReport compare(List<Path> sessionDirs) {
        List<SessionComparison> sessions = new ArrayList<>();
        for (Path dir : sessionDirs) {
            List<JsonNode> reports = loadSession(dir);
            List<MetricStat> metrics = new ArrayList<>();
            for (MetricDef def : METRIC_DEFS) {
                metrics.add(computeMetric(def, reports));
            }
            sessions.add(new SessionComparison(dir.getFileName().toString(), reports.size(), metrics));
        }
        return new ComparisonReport(sessions);
    }

    private List<JsonNode> loadSession(Path dir) {
        Path metricsDir = resolver.resolve(dir).metricsDir();
        try (Stream<Path> stream = Files.list(metricsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("run_\\d+_analysis\\.json"))
                    .sorted()
                    .map(reader::read)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("无法列出 analysis 文件: " + metricsDir, exception);
        }
    }

    private MetricStat computeMetric(MetricDef def, List<JsonNode> reports) {
        if (def.path().isEmpty()) {
            String fallback =
                    reports.isEmpty()
                            ? "-"
                            : text(at(reports.get(0), List.of("profitability", "stores_opened")))
                            + "/"
                            + text(at(reports.get(0), List.of("profitability", "store_reopens")));
            return new MetricStat(def.label(), null, null, 0, fallback);
        }
        List<Double> numbers = new ArrayList<>();
        String fallback = null;
        for (JsonNode report : reports) {
            JsonNode value = at(report, def.path());
            if (value == null || value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (fallback == null) {
                fallback = value.asText();
            }
            if (value.isNumber()) {
                numbers.add(value.asDouble());
            } else if (value.isBoolean()) {
                numbers.add(value.booleanValue() ? 1.0 : 0.0);
            }
        }
        if (numbers.isEmpty()) {
            return new MetricStat(def.label(), null, null, 0, fallback == null ? "-" : fallback);
        }
        double mean = numbers.stream().mapToDouble(Double::doubleValue).sum() / numbers.size();
        double std = 0.0;
        if (numbers.size() >= 2) {
            double sumSquares = 0.0;
            for (double value : numbers) {
                sumSquares += (value - mean) * (value - mean);
            }
            std = Math.sqrt(sumSquares / (numbers.size() - 1));
        }
        return new MetricStat(def.label(), mean, std, numbers.size(), null);
    }

    private static JsonNode at(JsonNode root, List<String> path) {
        JsonNode current = root;
        for (String key : path) {
            if (current == null) {
                return null;
            }
            current = current.get(key);
        }
        return current;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "-" : node.asText();
    }
}
