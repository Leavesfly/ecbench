package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.web.DimensionAxis.Diagnostic;
import io.github.ecommercebench.evaluation.io.AnalysisJsonReader;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import io.github.ecommercebench.evaluation.model.SessionLearning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 论文 §4.3 六维度视图的服务端计算：主评分（年末总资产倍数）、各维度排名指标值与 rank、rank crossings（附录 F.8）
 * 以及三类嵌套诊断块（八类活动带、五类欺诈支出、退货四层）。
 *
 * <p>刻意不复用 {@code RunComparator.METRIC_DEFS}：那份定义与 Python {@code compare_runs} 的比较表形状绑定，加行会破坏
 * 兼容性。本类直接读各 run 的 {@code analysis.json} 按论文口径重算，与 {@code CompareService} 并列、互不依赖。学习维度的值
 * 不在 analysis.json 内，取自阶段 A 挂在 {@link SessionComparison#learning()} 上的 {@link SessionLearning}。
 *
 * <p>取值口径：会话级为各 run 均值，null run 跳过不计入分母（与 {@code RunComparator} 一致）；rank 1 表示该维度最好，
 * 并列取 competition ranking（1,2,2,4），value 为 null 的会话排末位且 rank 为 null。
 */
public final class DimensionService {

    private static final Pattern ANALYSIS_FILE = Pattern.compile("run_(\\d+)_analysis\\.json");

    /** 论文 §E.2 的五类欺诈型，固定键序（未出现的补 0）。 */
    private static final List<String> FRAUD_TYPES =
            List.of("fake_urgency", "future_discount", "qty_bait", "quality_downgrade", "vip_fee");

    /** 论文 §E.4 的八类活动带，固定键序（与 ActivityBands.BAND_KEYS 一致）。 */
    private static final List<String> BANDS =
            List.of(
                    "waiting", "shipping", "listing", "withdraw",
                    "bargaining", "state_polling", "memory", "remainder");

    /** 论文 §E.5 的退货四层，固定键序。 */
    private static final List<String> RETURN_LAYERS =
            List.of(
                    "exp_return_rate_natural",
                    "exp_return_rate_from_pricing",
                    "exp_return_rate_from_ship_speed",
                    "exp_return_rate_from_defective_fraud");

    private static final List<String> SHIP_SPEEDS = List.of("fast", "standard", "slow");

    private final ObjectMapper mapper;
    private final AnalysisJsonReader reader = new AnalysisJsonReader();
    private final RunLayoutResolver resolver = new RunLayoutResolver();

    public DimensionService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 产出四段 JSON：{@code primaryScore}、{@code dimensions}、{@code rankCrossings}、{@code learning}。
     *
     * @param sessionDirs 会话目录，与 {@code report.sessions()} 同序同长
     */
    public ObjectNode node(List<Path> sessionDirs, ComparisonReport report) {
        List<String> names = new ArrayList<>(sessionDirs.size());
        List<List<JsonNode>> runsPerSession = new ArrayList<>(sessionDirs.size());
        for (Path dir : sessionDirs) {
            names.add(dir.getFileName().toString());
            runsPerSession.add(loadRuns(dir));
        }

        ObjectNode root = mapper.createObjectNode();
        Map<String, Double> primary = primaryScores(names, runsPerSession);
        Map<String, Integer> primaryRanks = rank(primary, false);
        root.set("primaryScore", primaryNode(names, primary, primaryRanks, runsPerSession));

        Map<String, Map<String, Integer>> dimensionRanks = new LinkedHashMap<>();
        ArrayNode dimensions = root.putArray("dimensions");
        for (DimensionAxis axis : DimensionAxis.values()) {
            Map<String, Double> values = dimensionValues(axis, names, runsPerSession, report);
            Map<String, Integer> ranks = rank(values, axis.lowerIsBetter());
            dimensionRanks.put(axis.key(), ranks);
            dimensions.add(dimensionNode(axis, names, values, ranks, runsPerSession));
        }

        root.set("rankCrossings", crossingsNode(names, primaryRanks, dimensionRanks));
        root.set("learning", learningNode(names, report));
        return root;
    }

    /** 列出该会话全部 run 的 analysis.json，按 run 序号升序。 */
    private List<JsonNode> loadRuns(Path sessionDir) {
        Path metrics = resolver.resolve(sessionDir).metricsDir();
        if (!Files.isDirectory(metrics)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(metrics)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> ANALYSIS_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(path -> runIndexOf(path.getFileName().toString())))
                    .map(reader::read)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("无法列出 analysis 文件: " + metrics, exception);
        }
    }

    private static int runIndexOf(String fileName) {
        Matcher matcher = ANALYSIS_FILE.matcher(fileName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 主评分 = reward.final_score 各 run 均值（论文 §3.7：年末总资产 ÷ 初始资本的资产倍数）。 */
    private static Map<String, Double> primaryScores(
            List<String> names, List<List<JsonNode>> runsPerSession) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            List<Double> samples = new ArrayList<>();
            for (JsonNode run : runsPerSession.get(i)) {
                Double score = DimensionAxis.at(run, "reward", "final_score");
                if (score != null) {
                    samples.add(score);
                }
            }
            values.put(names.get(i), meanOrNull(samples));
        }
        return values;
    }

    /** 各 run 均值；null 样本跳过，全 null 返回 null。 */
    private static Double meanOrNull(List<Double> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (double sample : samples) {
            sum += sample;
        }
        return sum / samples.size();
    }

    /**
     * 某维度在各会话上的值：analysis.json 能算的五个维度按 run 均值；学习维度取 {@link SessionLearning#anchorRatio()}
     * （它已是阶段 A 按「各 episode 比率的均值」聚合好的会话级值，不再二次平均）。
     */
    private static Map<String, Double> dimensionValues(
            DimensionAxis axis,
            List<String> names,
            List<List<JsonNode>> runsPerSession,
            ComparisonReport report) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            if (axis == DimensionAxis.LEARNING) {
                SessionComparison session = report.sessions().get(i);
                SessionLearning learning = session.learning();
                values.put(names.get(i), learning == null ? null : learning.anchorRatio());
                continue;
            }
            List<Double> samples = new ArrayList<>();
            for (JsonNode run : runsPerSession.get(i)) {
                Double raw = axis.raw(run);
                if (raw != null) {
                    samples.add(raw);
                }
            }
            values.put(names.get(i), meanOrNull(samples));
        }
        return values;
    }

    /**
     * 按维度方向排名：rank 1 = 最好；并列取 competition ranking（1,2,2,4）；null 值不入榜（map 中不出现该键）。
     */
    private static Map<String, Integer> rank(Map<String, Double> values, boolean lowerIsBetter) {
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (entry.getValue() != null) {
                scored.add(entry);
            }
        }
        scored.sort(
                (x, y) ->
                        lowerIsBetter
                                ? Double.compare(x.getValue(), y.getValue())
                                : Double.compare(y.getValue(), x.getValue()));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        Double previous = null;
        int previousRank = 0;
        for (int i = 0; i < scored.size(); i++) {
            Map.Entry<String, Double> entry = scored.get(i);
            int rank = previous != null && previous.doubleValue() == entry.getValue().doubleValue()
                    ? previousRank
                    : i + 1;
            ranks.put(entry.getKey(), rank);
            previous = entry.getValue();
            previousRank = rank;
        }
        return ranks;
    }

    /** 主评分段：标签、单位、取值来源字段，以及每会话的倍数、rank、年末总资产均值、破产 run 数与终止原因。 */
    private ObjectNode primaryNode(
            List<String> names,
            Map<String, Double> values,
            Map<String, Integer> ranks,
            List<List<JsonNode>> runsPerSession) {
        ObjectNode node = mapper.createObjectNode();
        node.put("labelZh", "年末总资产倍数");
        node.put("unit", "×");
        node.put("sourceField", "reward.final_score");
        ArrayNode rows = node.putArray("rows");
        for (String name : orderByRank(names, ranks)) {
            ObjectNode row = rows.addObject();
            row.put("name", name);
            putNumber(row, "value", values.get(name));
            putInteger(row, "rank", ranks.get(name));
            List<JsonNode> runs = runsPerSession.get(names.indexOf(name));
            putNumber(row, "finalBalance", meanOfRuns(runs, "profitability", "final_balance"));
            row.put("bankruptRuns", countTrue(runs, "profitability", "bankrupt"));
            putText(row, "termination", lastTermination(runs));
        }
        return node;
    }

    /** 各 run 在给定 path 上的数值均值；null 跳过，全 null 返回 null。 */
    private static Double meanOfRuns(List<JsonNode> runs, String... path) {
        List<Double> samples = new ArrayList<>();
        for (JsonNode run : runs) {
            Double value = DimensionAxis.at(run, path);
            if (value != null) {
                samples.add(value);
            }
        }
        return meanOrNull(samples);
    }

    /** 统计各 run 中该布尔字段为 true 的个数（破产 run 数，论文 §3.7：被截断的 run 仍计入均值）。 */
    private static int countTrue(List<JsonNode> runs, String... path) {
        int count = 0;
        for (JsonNode run : runs) {
            Double value = DimensionAxis.at(run, path);
            if (value != null && value == 1.0) {
                count++;
            }
        }
        return count;
    }

    /** 最后一个 run 的终止原因（reward.termination_reason）；无 run 或缺字段时返回 null。 */
    private static String lastTermination(List<JsonNode> runs) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            JsonNode reason = runs.get(i).path("reward").path("termination_reason");
            if (reason.isTextual()) {
                return reason.asText();
            }
        }
        return null;
    }

    /** 写入可空文本字段：null 时写 JSON null，保证前端形状稳定。 */
    private static void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /** 单个维度对象：口径元信息 + 排名指标 + 带 rank 的行 + 诊断指标 + 嵌套诊断块。 */
    private ObjectNode dimensionNode(
            DimensionAxis axis,
            List<String> names,
            Map<String, Double> values,
            Map<String, Integer> ranks,
            List<List<JsonNode>> runsPerSession) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", axis.key());
        node.put("labelZh", axis.labelZh());
        node.put("paperRef", axis.paperRef());
        ObjectNode rankMetric = node.putObject("rankMetric");
        rankMetric.put("label", axis.rankLabel());
        rankMetric.put("labelZh", axis.rankLabelZh());
        rankMetric.put("direction", axis.lowerIsBetter() ? "lower" : "higher");
        rankMetric.put("unit", axis.unit());

        ArrayNode rows = node.putArray("rows");
        for (String name : orderByRank(names, ranks)) {
            ObjectNode row = rows.addObject();
            row.put("name", name);
            putNumber(row, "value", values.get(name));
            putInteger(row, "rank", ranks.get(name));
        }

        ArrayNode diagnostics = node.putArray("diagnostics");
        for (Diagnostic diagnostic : axis.diagnostics()) {
            ObjectNode item = diagnostics.addObject();
            item.put("label", diagnostic.label());
            item.put("labelZh", diagnostic.labelZh());
            ObjectNode perSession = item.putObject("values");
            for (int i = 0; i < names.size(); i++) {
                List<Double> samples = new ArrayList<>();
                for (JsonNode run : runsPerSession.get(i)) {
                    Double value = DimensionAxis.at(run, diagnostic.path().toArray(String[]::new));
                    if (value != null) {
                        samples.add(value);
                    }
                }
                putNumber(perSession, names.get(i), meanOrNull(samples));
            }
        }

        node.set("blocks", blocksNode(axis, runsPerSession));
        return node;
    }

    /**
     * 三个嵌套诊断块（汇总口径：值为全部选中会话的全部 run 的均值，逐会话明细由 diagnostics 承担）：
     * 效率→八类活动带，欺诈→五类骗局支出，执行→退货四层与发货速度构成。
     */
    private ObjectNode blocksNode(DimensionAxis axis, List<List<JsonNode>> runsPerSession) {
        ObjectNode blocks = mapper.createObjectNode();
        switch (axis) {
            case EFFICIENCY -> blocks.set(
                    "callsByBand",
                    keyedBlock(
                            runsPerSession,
                            BANDS,
                            List.of("operational_efficiency", "calls_by_band"),
                            List.of("operational_efficiency", "calls_by_band_share")));
            case FRAUD -> blocks.set(
                    "spendByFraudType",
                    objectBlock(
                            runsPerSession,
                            FRAUD_TYPES,
                            List.of("fraud_identification", "spend_by_fraud_type"),
                            List.of("orders", "units", "spend")));
            case EXECUTION -> {
                blocks.set(
                        "returnLayers",
                        flatBlock(runsPerSession, RETURN_LAYERS, List.of("return_management")));
                blocks.set(
                        "shipSpeed",
                        flatBlock(
                                runsPerSession,
                                SHIP_SPEEDS,
                                List.of("fulfilment_quality", "ship_speed_counts")));
            }
            default -> {
                // 谈判/偿付/学习三个维度没有嵌套块，diagnostics 已覆盖
            }
        }
        return blocks;
    }

    /**
     * 「计数 + 占比」双字段汇总块：每个键输出 {@code {count, share}}，值为全部选中会话的全部 run 的均值。
     * 用于八类活动带（{@code calls_by_band} 与 {@code calls_by_band_share}）；逐会话明细由 diagnostics 承担。
     */
    private ObjectNode keyedBlock(
            List<List<JsonNode>> runsPerSession,
            List<String> keys,
            List<String> countPath,
            List<String> sharePath) {
        ObjectNode block = mapper.createObjectNode();
        for (String key : keys) {
            List<Double> counts = new ArrayList<>();
            List<Double> shares = new ArrayList<>();
            for (List<JsonNode> runs : runsPerSession) {
                for (JsonNode run : runs) {
                    Double count = DimensionAxis.at(run, withKey(countPath, key));
                    Double share = DimensionAxis.at(run, withKey(sharePath, key));
                    if (count != null) {
                        counts.add(count);
                    }
                    if (share != null) {
                        shares.add(share);
                    }
                }
            }
            ObjectNode entry = block.putObject(key);
            putNumber(entry, "count", meanOrNull(counts));
            putNumber(entry, "share", meanOrNull(shares));
        }
        return block;
    }

    /** 嵌套对象块：每个键输出其子字段（如 orders/units/spend）的跨会话均值；缺失键补 0。 */
    private ObjectNode objectBlock(
            List<List<JsonNode>> runsPerSession,
            List<String> keys,
            List<String> parentPath,
            List<String> fields) {
        ObjectNode block = mapper.createObjectNode();
        for (String key : keys) {
            ObjectNode entry = block.putObject(key);
            for (String field : fields) {
                List<Double> samples = new ArrayList<>();
                for (List<JsonNode> runs : runsPerSession) {
                    for (JsonNode run : runs) {
                        Double value =
                                DimensionAxis.at(
                                        run,
                                        concat(parentPath, List.of(key, field)));
                        if (value != null) {
                            samples.add(value);
                        }
                    }
                }
                // 论文 §E.2 要求五类骗局全部出现，未记录的补 0（与 MetricsJsonWriter 预置全键一致）
                putNumber(entry, field, samples.isEmpty() ? 0.0 : meanOrNull(samples));
            }
        }
        return block;
    }

    /** 平铺块：父路径下每个键的跨会话均值；缺失键补 0。 */
    private ObjectNode flatBlock(
            List<List<JsonNode>> runsPerSession, List<String> keys, List<String> parentPath) {
        ObjectNode block = mapper.createObjectNode();
        for (String key : keys) {
            List<Double> samples = new ArrayList<>();
            for (List<JsonNode> runs : runsPerSession) {
                for (JsonNode run : runs) {
                    Double value = DimensionAxis.at(run, concat(parentPath, List.of(key)));
                    if (value != null) {
                        samples.add(value);
                    }
                }
            }
            putNumber(block, key, samples.isEmpty() ? 0.0 : meanOrNull(samples));
        }
        return block;
    }

    /** rank crossings（附录 F.8）：每会话一行，含主评分 rank 与六维度 rank。 */
    private ArrayNode crossingsNode(
            List<String> names,
            Map<String, Integer> primaryRanks,
            Map<String, Map<String, Integer>> dimensionRanks) {
        ArrayNode array = mapper.createArrayNode();
        List<String> ordered = new ArrayList<>(names);
        ordered.sort(
                Comparator.comparingInt(
                        name -> primaryRanks.getOrDefault(name, Integer.MAX_VALUE)));
        for (String name : ordered) {
            ObjectNode row = array.addObject();
            row.put("name", name);
            putInteger(row, "primary", primaryRanks.get(name));
            ObjectNode ranks = row.putObject("ranks");
            for (DimensionAxis axis : DimensionAxis.values()) {
                putInteger(ranks, axis.key(), dimensionRanks.get(axis.key()).get(name));
            }
        }
        return array;
    }

    /** 学习段：逐会话输出阶段 A 的 SessionLearning；无产物时全 null、计数 0。 */
    private ObjectNode learningNode(List<String> names, ComparisonReport report) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < names.size(); i++) {
            SessionLearning learning = report.sessions().get(i).learning();
            ObjectNode entry = node.putObject(names.get(i));
            putNumber(entry, "anchorRatio", learning == null ? null : learning.anchorRatio());
            putNumber(entry, "anchorRegret", learning == null ? null : learning.anchorRegret());
            putNumber(entry, "newLow", learning == null ? null : learning.newLow());
            putNumber(entry, "newLowRatioZ", learning == null ? null : learning.newLowRatioZ());
            putNumber(entry, "zAnchor", learning == null ? null : learning.zAnchor());
            putNumber(entry, "seHalfLift", learning == null ? null : learning.seHalfLift());
            putNumber(entry, "seTrend", learning == null ? null : learning.seTrend());
            putNumber(entry, "fraudHalfLift", learning == null ? null : learning.fraudHalfLift());
            entry.put("episodes", learning == null ? 0 : learning.episodes());
            entry.put("repeatPairs", learning == null ? 0 : learning.repeatPairs());
            entry.put("reorders", learning == null ? 0 : learning.reorders());
        }
        return node;
    }

    /** 按 rank 升序排列会话名；无 rank（值为 null）的排在最后，保持输入序。 */
    private static List<String> orderByRank(List<String> names, Map<String, Integer> ranks) {
        List<String> ordered = new ArrayList<>(names);
        Set<String> ranked = new LinkedHashSet<>(ranks.keySet());
        ordered.sort(
                (x, y) -> {
                    boolean hasX = ranked.contains(x);
                    boolean hasY = ranked.contains(y);
                    if (hasX != hasY) {
                        return hasX ? -1 : 1;
                    }
                    return hasX ? Integer.compare(ranks.get(x), ranks.get(y)) : 0;
                });
        return ordered;
    }

    private static String[] withKey(List<String> path, String key) {
        return concat(path, List.of(key));
    }

    private static String[] concat(List<String> prefix, List<String> suffix) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(suffix);
        return all.toArray(String[]::new);
    }

    private static void putNumber(ObjectNode node, String field, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putInteger(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.intValue());
        }
    }
}
