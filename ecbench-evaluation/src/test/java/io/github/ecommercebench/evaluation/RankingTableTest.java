package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;
import io.github.ecommercebench.evaluation.model.NormalizedProfile.ModelProfile;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 排名表测试：按主要得分（年末总资产）降序排名，缺样本排末位；文本/CSV/JSON 三种渲染含模型名、排名与七轴归一化值。
 */
class RankingTableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Double> vec(double... values) {
        Double[] boxed = new Double[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return Arrays.asList(boxed);
    }

    private static NormalizedProfile profile() {
        return new NormalizedProfile(
                List.of(
                        new ModelProfile(
                                "modelLow", vec(100000, 0, 0, 0, 0, 0, 0), vec(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6)),
                        new ModelProfile(
                                "modelHigh",
                                vec(200000, 0, 0, 0, 0, 0, 0),
                                vec(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4))),
                vec(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
    }

    @Test
    void ranksByPrimaryScoreDescending() {
        List<RankingTable.RankedModel> ranked = new RankingTable().rank(profile());
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).name()).isEqualTo("modelHigh");
        assertThat(ranked.get(0).rank()).isEqualTo(1);
        assertThat(ranked.get(0).primaryRaw())
                .isCloseTo(200000.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(ranked.get(1).name()).isEqualTo("modelLow");
        assertThat(ranked.get(1).rank()).isEqualTo(2);
    }

    @Test
    void ranksNaNPrimaryLast() {
        NormalizedProfile p =
                new NormalizedProfile(
                        List.of(
                                new ModelProfile(
                                        "missing", vec(Double.NaN, 0, 0, 0, 0, 0, 0), vec(0, 0, 0, 0, 0, 0, 0)),
                                new ModelProfile(
                                        "present", vec(50000, 0, 0, 0, 0, 0, 0), vec(1, 0, 0, 0, 0, 0, 0))),
                        vec(0, 0, 0, 0, 0, 0, 0));
        List<RankingTable.RankedModel> ranked = new RankingTable().rank(p);
        assertThat(ranked.get(0).name()).isEqualTo("present");
        assertThat(ranked.get(1).name()).isEqualTo("missing");
        assertThat(ranked.get(1).rank()).isEqualTo(2);
    }

    @Test
    void rendersNormalizedByAxisKeyedMap() {
        RankingTable.RankedModel top = new RankingTable().rank(profile()).get(0);
        assertThat(top.normalizedByAxis())
                .containsKeys(
                        "primary", "negotiation", "fraud", "solvency", "efficiency", "execution", "learning");
        assertThat(top.normalizedByAxis().get("primary"))
                .isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void csvHasHeaderAndOneRowPerModel() {
        String csv = new RankingTable().toCsv(profile());
        List<String> lines = Arrays.stream(csv.split("\n")).filter(s -> !s.isBlank()).toList();
        assertThat(lines.get(0)).startsWith("rank,model,primary_raw");
        assertThat(lines).hasSize(3); // 表头 + 2 模型
        assertThat(lines.get(1)).contains("modelHigh");
    }

    @Test
    void jsonParsesWithAxesAndModels() throws Exception {
        String json = new RankingTable().toJson(profile(), MAPPER);
        JsonNode root = MAPPER.readTree(json);
        assertThat(root.get("axes").isArray()).isTrue();
        assertThat(root.get("axes")).hasSize(ProfileAxis.values().length);
        assertThat(root.get("models").isArray()).isTrue();
        assertThat(root.get("models")).hasSize(2);
        assertThat(root.get("models").get(0).get("name").asText()).isEqualTo("modelHigh");
        assertThat(root.get("models").get(0).get("rank").asInt()).isEqualTo(1);
    }

    @Test
    void textContainsHeaderModelNamesAndRanks() {
        String text = new RankingTable().toText(profile());
        assertThat(text).contains("modelHigh").contains("modelLow");
        assertThat(text).contains("rank");
    }
}
