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
            "typical_gross_margin 是定性指标（低/中/高）——你的实际毛利取决于谈判能力、零售价与退货情况。"
                    + "monthly_sales_index 是 12 个元素的列表（1 月..12 月，基准=100）。";
    private static final String STORE_DETAIL_NOTE =
            "调用 market_search(category='<name>') 可查看某个子品类的退货率区间、典型毛利区间与典型销量区间，"
                    + "再决定备货。";

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
                            ? "店铺类型 '"
                            + storeType
                            + "' 的有效子品类："
                            + pythonList(subcategoryNames(storeType))
                            : "未知子品类。可先不带参数调用 market_search() 以列出店铺类型及其子品类。";
            response.put("note", "没有名为 '" + category + "' 的子品类。 " + hint);
            return response;
        }
        if (storeType != null && !storeType.isBlank() && !params.storeType().equals(storeType)) {
            response.set("results", mapper.createArrayNode());
            response.put(
                    "note",
                    "子品类 '"
                            + category
                            + "' 属于店铺类型 '"
                            + params.storeType()
                            + "'，而不是 '"
                            + storeType
                            + "'。");
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
                    "未知店铺类型 '"
                            + storeType
                            + "'。可选："
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
                "全部店铺类型概览（你最多可开设 "
                        + maxStores
                        + " 家店铺）。'tier' 与 'daily_ops_cost' 显示每家店铺的每日运营成本；每家店都按全额支付运营成本"
                        + "（没有多店折扣）。'store_advantage' 概括其独特优势 + 劣势；"
                        + "'monthly_sales_index'（1 月..12 月，基准=100）展示其季节性形态。"
                        + "用 market_search(store_type='<id>') 下钻查看某店铺的子品类，再用 "
                        + "market_search(category='<name>') 查看某个子品类的退货率/毛利/销量明细。"
                        + "不同店铺类型以不同方式取胜——选择你最擅长经营的那些。");
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
        String marginLabel = marginMid < 0.20 ? "低" : marginMid < 0.35 ? "中" : "高";
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
                        + " 件/月（全平台口径；单店只能实现其中一部分）");
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
