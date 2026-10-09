package io.github.ecommercebench.domain.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.catalog.MarketGuidance.StorePlaybook;
import io.github.ecommercebench.domain.error.DataValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从 `data/store_playbook.json` 加载 market_search 的静态定性指引。 */
public final class StorePlaybookLoader {

  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * 读取并解析市场指引 JSON，聚合为 {@link MarketGuidance}。
   *
   * <p>文件缺失或解析（含层级号转换）失败时统一抛 {@code DataValidationException}， 与 CSV 加载器的启动期校验行为保持一致。
   */
  public MarketGuidance load(Path jsonPath) {
    if (!Files.exists(jsonPath)) {
      throw new DataValidationException("缺少市场指引文件: " + jsonPath);
    }
    try {
      JsonNode root = mapper.readTree(Files.readString(jsonPath));
      return new MarketGuidance(
          profitPotential(root.path("profit_potential_by_tier")),
          stringMap(root.path("store_advantage_axis")),
          playbook(root.path("playbook")));
    } catch (IOException | NumberFormatException ex) {
      throw new DataValidationException("市场指引文件解析失败: " + jsonPath, ex);
    }
  }

  private Map<Integer, String> profitPotential(JsonNode node) {
    Map<Integer, String> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> values.put(Integer.parseInt(entry.getKey()), entry.getValue().asText()));
    return values;
  }

  private Map<String, String> stringMap(JsonNode node) {
    Map<String, String> values = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
    return values;
  }

  private Map<String, StorePlaybook> playbook(JsonNode node) {
    Map<String, StorePlaybook> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              JsonNode pb = entry.getValue();
              values.put(
                  entry.getKey(),
                  new StorePlaybook(
                      textList(pb.path("strengths")),
                      textList(pb.path("challenges")),
                      textList(pb.path("tips"))));
            });
    return values;
  }

  private List<String> textList(JsonNode array) {
    List<String> values = new ArrayList<>();
    if (array.isArray()) {
      array.forEach(item -> values.add(item.asText()));
    }
    return values;
  }
}
