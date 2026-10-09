package io.github.ecommercebench.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;
import io.github.ecommercebench.evaluation.model.NormalizedProfile.ModelProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨模型排名表：按主要得分（年末总资产，{@link ProfileAxis#PRIMARY} 原始值）降序排名，缺样本（NaN）排末位。
 *
 * <p>提供文本表（stdout）、CSV 与 JSON 三种渲染，每模型含排名、名称、主要得分原始值与七轴归一化值（NaN 在文本/CSV 记为 {@code n/a}、 在 JSON 记为
 * null）。
 */
public final class RankingTable {

    /**
     * 单个模型的排名行：排名、名称、主要得分原始值、按轴键索引的归一化值。
     */
    public record RankedModel(
            int rank, String name, double primaryRaw, Map<String, Double> normalizedByAxis) {
        public RankedModel {
            normalizedByAxis = Collections.unmodifiableMap(new LinkedHashMap<>(normalizedByAxis));
        }
    }

    public List<RankedModel> rank(NormalizedProfile profile) {
        ProfileAxis[] axes = ProfileAxis.values();
        List<ModelProfile> sorted = new ArrayList<>(profile.models());
        sorted.sort(RankingTable::byPrimaryDesc);
        List<RankedModel> ranked = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ModelProfile model = sorted.get(i);
            Map<String, Double> byAxis = new LinkedHashMap<>();
            for (int a = 0; a < axes.length; a++) {
                byAxis.put(axes[a].key(), model.normalizedValues().get(a));
            }
            ranked.add(new RankedModel(i + 1, model.name(), model.rawValues().get(0), byAxis));
        }
        return ranked;
    }

    public String toText(NormalizedProfile profile) {
        ProfileAxis[] axes = ProfileAxis.values();
        StringBuilder header =
                new StringBuilder(String.format("%-5s %-24s %14s", "rank", "model", "score"));
        for (ProfileAxis axis : axes) {
            header.append(String.format(" %10s", axis.key()));
        }
        StringBuilder out = new StringBuilder(header).append('\n');
        for (RankedModel model : rank(profile)) {
            StringBuilder row =
                    new StringBuilder(
                            String.format(
                                    "%-5d %-24s %14s",
                                    model.rank(), truncate(model.name()), fmtRaw(model.primaryRaw())));
            for (ProfileAxis axis : axes) {
                row.append(String.format(" %10s", fmt(model.normalizedByAxis().get(axis.key()))));
            }
            out.append(row).append('\n');
        }
        return out.toString();
    }

    public String toCsv(NormalizedProfile profile) {
        ProfileAxis[] axes = ProfileAxis.values();
        StringBuilder header = new StringBuilder("rank,model,primary_raw");
        for (ProfileAxis axis : axes) {
            header.append(',').append(axis.key());
        }
        StringBuilder out = new StringBuilder(header).append('\n');
        for (RankedModel model : rank(profile)) {
            out.append(model.rank())
                    .append(',')
                    .append(model.name())
                    .append(',')
                    .append(fmtRaw(model.primaryRaw()));
            for (ProfileAxis axis : axes) {
                out.append(',').append(fmt(model.normalizedByAxis().get(axis.key())));
            }
            out.append('\n');
        }
        return out.toString();
    }

    public String toJson(NormalizedProfile profile, ObjectMapper mapper) {
        ProfileAxis[] axes = ProfileAxis.values();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode axesNode = mapper.createArrayNode();
        for (ProfileAxis axis : axes) {
            ObjectNode node = mapper.createObjectNode();
            node.put("key", axis.key());
            node.put("label", axis.label());
            node.put("lower_is_better", axis.lowerIsBetter());
            axesNode.add(node);
        }
        root.set("axes", axesNode);

        ArrayNode medianNode = mapper.createArrayNode();
        for (Double value : profile.medianNormalized()) {
            addNumber(medianNode, value);
        }
        root.set("median", medianNode);

        ArrayNode modelsNode = mapper.createArrayNode();
        for (RankedModel model : rank(profile)) {
            ObjectNode node = mapper.createObjectNode();
            node.put("rank", model.rank());
            node.put("name", model.name());
            if (Double.isNaN(model.primaryRaw())) {
                node.putNull("primary_raw");
            } else {
                node.put("primary_raw", model.primaryRaw());
            }
            ObjectNode normalized = mapper.createObjectNode();
            for (ProfileAxis axis : axes) {
                putNumber(normalized, axis.key(), model.normalizedByAxis().get(axis.key()));
            }
            node.set("normalized", normalized);
            modelsNode.add(node);
        }
        root.set("models", modelsNode);

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("排名表序列化失败", exception);
        }
    }

    private static int byPrimaryDesc(ModelProfile x, ModelProfile y) {
        double px = x.rawValues().get(0);
        double py = y.rawValues().get(0);
        boolean nx = Double.isNaN(px);
        boolean ny = Double.isNaN(py);
        if (nx && ny) {
            return 0;
        }
        if (nx) {
            return 1;
        }
        if (ny) {
            return -1;
        }
        return Double.compare(py, px);
    }

    private static void addNumber(ArrayNode node, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.addNull();
        } else {
            node.add(value);
        }
    }

    private static void putNumber(ObjectNode node, String field, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String fmt(Double value) {
        return value == null || Double.isNaN(value) ? "n/a" : String.format("%.4f", value);
    }

    private static String fmtRaw(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.2f", value);
    }

    private static String truncate(String name) {
        return name.length() <= 24 ? name : name.substring(0, 24);
    }
}
