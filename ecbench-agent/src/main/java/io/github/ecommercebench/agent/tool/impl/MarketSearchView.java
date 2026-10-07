package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.MarketGuidance;
import io.github.ecommercebench.domain.catalog.StoreTypeConfig;
import io.github.ecommercebench.simulation.EconomicRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * market_search 的三级渐进式披露视图构造器，逐键端口自 Python `EcommerceEnv.market_search`。
 *
 * <p>只读地组合目录（店型、类别参数）、静态定性指引（剧本/利润潜力/优势轴）与经济常量，不触碰任何仿真状态， 因此属于展示层而非业务计算。
 */
final class MarketSearchView {

  private static final String SUBCATEGORY_NOTE =
      "typical_gross_margin is a qualitative indicator (low/moderate/high) — your actual margin "
          + "depends on negotiation skill, your retail price, and returns. monthly_sales_index is "
          + "a 12-entry list (Jan..Dec, baseline=100).";
  private static final String STORE_DETAIL_NOTE =
      "Call market_search(category='<name>') for a sub-category's return-rate range, typical "
          + "gross-margin range, and typical sales range before deciding what to stock.";

  private final CatalogData catalog;
  private final MarketGuidance guidance;
  private final int maxStores;
  private final ObjectMapper mapper;

  MarketSearchView(
      CatalogData catalog, MarketGuidance guidance, int maxStores, ObjectMapper mapper) {
    this.catalog = catalog;
    this.guidance = guidance;
    this.maxStores = maxStores;
    this.mapper = mapper;
  }

  ObjectNode search(String storeType, String category) {
    if (category != null && !category.isBlank()) {
      return subcategoryLevel(storeType, category);
    }
    if (storeType != null && !storeType.isBlank()) {
      return storeLevel(storeType);
    }
    return overviewLevel();
  }

  private ObjectNode subcategoryLevel(String storeType, String category) {
    ObjectNode response = mapper.createObjectNode();
    response.put("view", "subcategory_detail");
    CategoryParams params = catalog.categoryParams().get(category);
    if (params == null) {
      response.set("results", mapper.createArrayNode());
      String hint =
          storeType != null && !storeType.isBlank()
              ? "Valid sub-categories for '"
                  + storeType
                  + "': "
                  + pythonList(subcategoryNames(storeType))
              : "Unknown sub-category. Call market_search() with no arguments to list store types "
                  + "and their sub-categories.";
      response.put("note", "No sub-category named '" + category + "'. " + hint);
      return response;
    }
    if (storeType != null && !storeType.isBlank() && !params.storeType().equals(storeType)) {
      response.set("results", mapper.createArrayNode());
      response.put(
          "note",
          "Sub-category '"
              + category
              + "' belongs to store type '"
              + params.storeType()
              + "', not '"
              + storeType
              + "'.");
      return response;
    }
    response.set("detail", subcategoryDetail(category, params));
    response.put("note", SUBCATEGORY_NOTE);
    return response;
  }

  private ObjectNode storeLevel(String storeType) {
    ObjectNode response = mapper.createObjectNode();
    response.put("view", "store_detail");
    StoreTypeConfig type = catalog.storeTypes().get(storeType);
    if (type == null) {
      response.set("results", mapper.createArrayNode());
      response.put(
          "note",
          "Unknown store type '"
              + storeType
              + "'. Valid: "
              + pythonList(List.copyOf(catalog.storeTypes().keySet())));
      return response;
    }
    List<String> subcats = subcategoryNames(storeType);
    MarketGuidance.StorePlaybook playbook = guidance.playbook(storeType);
    response.put("store_type", storeType);
    response.put("store_name", type.storeTypeName());
    response.put("tier", type.tier());
    response.put("profit_potential", guidance.profitPotential(type.tier()));
    response.put(
        "daily_ops_cost", EconomicRules.operationsCost(type.tier()).amount().doubleValue());
    response.put("store_advantage", guidance.storeAdvantage(storeType));
    response.set("strengths", stringArray(playbook.strengths()));
    response.set("challenges", stringArray(playbook.challenges()));
    response.set("operating_tips", stringArray(playbook.tips()));
    ArrayNode cards = mapper.createArrayNode();
    subcats.forEach(name -> cards.add(subcategoryBasic(name)));
    response.set("subcategories", cards);
    response.put("count", subcats.size());
    response.put("note", STORE_DETAIL_NOTE);
    addSeasonSummary(response, type);
    return response;
  }

  private ObjectNode overviewLevel() {
    ArrayNode overview = mapper.createArrayNode();
    for (var entry : catalog.storeTypes().entrySet()) {
      StoreTypeConfig type = entry.getValue();
      List<String> subcats = subcategoryNames(entry.getKey());
      MarketGuidance.StorePlaybook playbook = guidance.playbook(entry.getKey());
      ObjectNode row = mapper.createObjectNode();
      row.put("store_type", entry.getKey());
      row.put("store_name", type.storeTypeName());
      row.put("tier", type.tier());
      row.put("profit_potential", guidance.profitPotential(type.tier()));
      row.put("daily_ops_cost", EconomicRules.operationsCost(type.tier()).amount().doubleValue());
      row.put("store_advantage", guidance.storeAdvantage(entry.getKey()));
      row.set("strengths", stringArray(playbook.strengths()));
      row.set("challenges", stringArray(playbook.challenges()));
      row.set("operating_tips", stringArray(playbook.tips()));
      row.put("num_subcategories", subcats.size());
      row.set("subcategories", stringArray(subcats));
      addSeasonSummary(row, type);
      overview.add(row);
    }
    ObjectNode response = mapper.createObjectNode();
    response.put("view", "store_type_overview");
    response.set("store_types", overview);
    response.put("count", overview.size());
    response.put(
        "note",
        "Overview of all store types (you may open up to "
            + maxStores
            + " stores). 'tier' and 'daily_ops_cost' show the daily ops cost per store; each store "
            + "pays its full ops cost (there is no multi-store discount). 'store_advantage' "
            + "summarises its distinctive strength + weakness; 'monthly_sales_index' (Jan..Dec, "
            + "baseline=100) shows its seasonal shape. Drill down with market_search(store_type="
            + "'<id>') to see a store's sub-categories, then market_search(category='<name>') for "
            + "one sub-category's return-rate / margin / sales detail. Different store types win "
            + "in different ways — pick the ones you can run best.");
    return response;
  }

  private ObjectNode subcategoryBasic(String category) {
    ObjectNode card = mapper.createObjectNode();
    CategoryParams params = catalog.categoryParams().get(category);
    card.put("category", category);
    card.put("reference_price_range", priceRange(params));
    card.put("default_size", params == null ? "" : params.defaultSize());
    return card;
  }

  private ObjectNode subcategoryDetail(String category, CategoryParams params) {
    ObjectNode detail = mapper.createObjectNode();
    String storeType = params.storeType();
    double marginMid =
        Math.max(
            0.0, 1.0 - params.wholesaleRatio().add(params.costFloorRatio()).doubleValue() / 2.0);
    String marginLabel = marginMid < 0.20 ? "low" : marginMid < 0.35 ? "moderate" : "high";
    EconomicRules.SizeCost costs = EconomicRules.sizeCost(params.defaultSize());
    StoreTypeConfig type = catalog.storeTypes().get(storeType);
    detail.put("category", category);
    detail.put("store_type", storeType);
    detail.put("store_name", type == null ? storeType : type.storeTypeName());
    detail.put("default_size", params.defaultSize());
    detail.put("reference_price_range", priceRange(params));
    detail.put("return_rate_note", params.returnRateDescription());
    detail.put("typical_gross_margin", marginLabel);
    detail.put(
        "typical_monthly_sales_range",
        params.monthlySalesMin()
            + "-"
            + params.monthlySalesMax()
            + " units/month (platform-wide; a single store realises only a fraction)");
    detail.put("shipping_cost_per_unit", costs.shipping().amount().doubleValue());
    detail.put("storage_cost_per_unit_per_day", costs.storagePerDay().amount().doubleValue());
    detail.put("store_advantage", guidance.storeAdvantage(storeType));
    if (type != null) {
      addSeasonSummary(detail, type);
    }
    return detail;
  }

  private void addSeasonSummary(ObjectNode target, StoreTypeConfig type) {
    ArrayNode index = mapper.createArrayNode();
    int[] values = new int[12];
    List<BigDecimal> seasonality = type.seasonality();
    for (int month = 0; month < 12; month++) {
      BigDecimal factor = month < seasonality.size() ? seasonality.get(month) : BigDecimal.ONE;
      values[month] =
          factor.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
      index.add(values[month]);
    }
    target.set("monthly_sales_index", index);
    target.put("season_peak_month", firstIndexOfExtremum(values, true) + 1);
    target.put("season_low_month", firstIndexOfExtremum(values, false) + 1);
  }

  private int firstIndexOfExtremum(int[] values, boolean max) {
    int best = 0;
    for (int i = 1; i < values.length; i++) {
      if (max ? values[i] > values[best] : values[i] < values[best]) {
        best = i;
      }
    }
    return best;
  }

  private String priceRange(CategoryParams params) {
    if (params == null) {
      return "¥?-?";
    }
    return "¥"
        + params.referencePriceMin().toPlainString()
        + "-"
        + params.referencePriceMax().toPlainString();
  }

  private List<String> subcategoryNames(String storeType) {
    StoreTypeConfig type = catalog.storeTypes().get(storeType);
    return type == null ? List.of() : type.allowedCategories();
  }

  private ArrayNode stringArray(List<String> values) {
    ArrayNode array = mapper.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  private String pythonList(List<String> values) {
    return values.stream()
        .map(value -> "'" + value + "'")
        .collect(Collectors.joining(", ", "[", "]"));
  }
}
