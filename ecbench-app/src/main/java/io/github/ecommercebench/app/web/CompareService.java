package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.CrossModelProfiler;
import io.github.ecommercebench.evaluation.ProfileAxis;
import io.github.ecommercebench.evaluation.RankingTable;
import io.github.ecommercebench.evaluation.RankingTable.RankedModel;
import io.github.ecommercebench.evaluation.RunComparator;
import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;
import io.github.ecommercebench.evaluation.model.NormalizedProfile.ModelProfile;

import java.nio.file.Path;
import java.util.List;

/**
 * 产出 dashboard「多 run 对比」所需的 JSON：会话指标矩阵、七轴归一化雷达与跨会话排名。
 *
 * <p>数值口径完全沿用 evaluation 模块，本类只做序列化：{@link RunComparator} 聚合均值/标准差，
 * {@link CrossModelProfiler} 做 min-max 归一化，{@link RankingTable} 按主分降序排名。
 * 不可计算的指标与缺失轴输出 JSON {@code null}（与 {@link RankingTable} 的 NaN→null 约定一致），
 * 轴顺序与 {@link ProfileAxis#values()} 一致。
 *
 * <p>另外追加论文 §3.7/§4.3 的维度视图四段（{@code primaryScore}/{@code dimensions}/{@code rankCrossings}/
 * {@code learning}），由 {@link DimensionService} 计算；既有三段的形状与取值保持不变，以免破坏前端与回归测试。
 */
public final class CompareService {

    private final ObjectMapper mapper;
    private final DimensionService dimensions;

    public CompareService(ObjectMapper mapper) {
        this(mapper, new DimensionService(mapper));
    }

    public CompareService(ObjectMapper mapper, DimensionService dimensions) {
        this.mapper = mapper;
        this.dimensions = dimensions;
    }

    /**
     * 对给定会话目录依次输出聚合指标、归一化雷达、排名，以及论文 §4.3 的维度视图四段；空输入返回同形的空结构。
     */
    public JsonNode compare(List<Path> sessionDirs) {
        ComparisonReport report = new RunComparator().compare(sessionDirs);
        NormalizedProfile profile = new CrossModelProfiler().profile(report);
        ObjectNode root = mapper.createObjectNode();
        root.set("sessions", sessionsNode(report));
        root.set("radar", radarNode(profile));
        root.set("ranking", rankingNode(new RankingTable().rank(profile)));
        ObjectNode paper = dimensions.node(sessionDirs, report);
        paper.fieldNames().forEachRemaining(name -> root.set(name, paper.get(name)));
        return root;
    }

    private ArrayNode sessionsNode(ComparisonReport report) {
        ArrayNode sessions = mapper.createArrayNode();
        for (SessionComparison session : report.sessions()) {
            ObjectNode node = sessions.addObject();
            node.put("name", session.name());
            node.put("runCount", session.runCount());
            ArrayNode metrics = node.putArray("metrics");
            for (MetricStat stat : session.metrics()) {
                ObjectNode metric = metrics.addObject();
                metric.put("label", stat.label());
                putNumber(metric, "mean", stat.mean());
                putNumber(metric, "std", stat.std());
                metric.put("n", stat.n());
                metric.put("fallback", stat.fallback());
            }
        }
        return sessions;
    }

    private ObjectNode radarNode(NormalizedProfile profile) {
        ObjectNode radar = mapper.createObjectNode();
        ArrayNode axes = radar.putArray("axes");
        for (ProfileAxis axis : ProfileAxis.values()) {
            axes.add(axis.label());
        }
        ArrayNode series = radar.putArray("series");
        for (ModelProfile model : profile.models()) {
            ObjectNode node = series.addObject();
            node.put("name", model.name());
            ArrayNode values = node.putArray("values");
            for (Double value : model.normalizedValues()) {
                addNumber(values, value);
            }
        }
        ArrayNode median = radar.putArray("median");
        for (Double value : profile.medianNormalized()) {
            addNumber(median, value);
        }
        return radar;
    }

    private ArrayNode rankingNode(List<RankedModel> ranked) {
        ArrayNode array = mapper.createArrayNode();
        for (RankedModel model : ranked) {
            ObjectNode node = array.addObject();
            node.put("rank", model.rank());
            node.put("name", model.name());
            putNumber(node, "score", nanToNull(model.primaryRaw()));
        }
        return array;
    }

    private static Double nanToNull(double value) {
        return Double.isNaN(value) ? null : value;
    }

    private static void putNumber(ObjectNode node, String field, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void addNumber(ArrayNode node, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.addNull();
        } else {
            node.add(value);
        }
    }
}
