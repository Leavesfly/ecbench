package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.SimulationEngine;
import io.github.ecommercebench.simulation.state.StoreState;
import java.util.LinkedHashSet;

/**
 * check_store_status：省略 store_id 返回全店摘要，指定则返回单店明细，逐键对齐 Python `get_store_status`。
 *
 * <p>单店明细包含昨日营收/退货/运费汇总与逐商品销量，退货按 SKU 归因来自 {@link StoreState#yesterdaySales()}。
 */
public final class CheckStoreStatusTool implements EcommerceTool {

  private static final String NAME = "check_store_status";

  private final ToolDefinition definition = ToolSchemas.load(NAME);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ToolDefinition definition() {
    return definition;
  }

  @Override
  public ObjectNode execute(JsonNode args, ToolExecutionContext context) {
    String storeId = ToolArgs.string(args, "store_id");
    SimulationEngine engine = context.engine();
    ObjectMapper mapper = context.mapper();
    ObjectNode out = mapper.createObjectNode();
    if (storeId == null || storeId.isBlank()) {
      return summary(out, engine);
    }
    StoreState store = engine.state().store(storeId);
    if (store == null) {
      out.put("error", "Store '" + storeId + "' not found.");
      return out;
    }
    return detail(out, engine, store);
  }

  private ObjectNode summary(ObjectNode out, SimulationEngine engine) {
    var stores = engine.state().stores().values();
    int open = (int) stores.stream().filter(StoreState::isOpen).count();
    out.put("open_stores", open);
    out.put("max_stores", engine.state().maxStores());
    ArrayNode array = out.putArray("stores");
    for (StoreState store : stores) {
      ObjectNode node = array.addObject();
      node.put("store_id", store.storeId());
      node.put("store_type", store.storeType());
      node.put("store_name", store.storeName());
      node.put("is_open", store.isOpen());
      node.put("reputation", ToolArgs.round(store.reputation(), 3));
      node.put("total_products", store.inventory().size());
      node.put(
          "total_inventory", store.inventory().values().stream().mapToInt(Integer::intValue).sum());
      node.put("promotion", store.promotionActive());
    }
    return out;
  }

  private ObjectNode detail(ObjectNode out, SimulationEngine engine, StoreState store) {
    out.put("store_id", store.storeId());
    out.put("store_type", store.storeType());
    out.put("store_name", store.storeName());
    out.put("is_open", store.isOpen());
    out.put("opened_date", store.openedDate().toString());
    out.put("reputation", ToolArgs.round(store.reputation(), 3));
    out.put("promotion", store.promotionActive());
    out.put("promotion_discount", store.promotionDiscount());

    double revenue = 0.0;
    double refunds = 0.0;
    double shipping = 0.0;
    int returnsCount = 0;
    for (StoreState.DailySale sale : store.yesterdaySales().values()) {
      revenue += sale.revenue().amount().doubleValue();
      returnsCount += sale.returned();
      refunds += sale.refundAmount().amount().doubleValue();
      shipping += sale.shippingCost().amount().doubleValue();
    }
    ObjectNode yesterday = out.putObject("yesterday_summary");
    yesterday.put("revenue", ToolArgs.round(revenue, 2));
    yesterday.put("returns_count", returnsCount);
    yesterday.put("refunds", ToolArgs.round(refunds, 2));
    yesterday.put("shipping_cost", ToolArgs.round(shipping, 2));
    yesterday.put("net", ToolArgs.round(revenue - refunds - shipping, 2));

    LinkedHashSet<String> productIds = new LinkedHashSet<>(store.inventory().keySet());
    productIds.addAll(store.yesterdaySales().keySet());
    ArrayNode products = out.putArray("products");
    for (String productId : productIds) {
      Product product = engine.product(productId);
      StoreState.DailySale sale = store.yesterdaySales().get(productId);
      ObjectNode node = products.addObject();
      node.put("product_id", productId);
      node.put("title", ToolArgs.truncate(product == null ? "" : product.title(), 60));
      node.put("category", product == null ? "" : product.category());
      node.put("quantity", store.inventory().getOrDefault(productId, 0));
      node.put(
          "retail_price",
          store.prices().getOrDefault(productId, Money.ZERO).amount().doubleValue());
      node.put("yesterday_sold", sale == null ? 0 : sale.quantity());
      node.put("yesterday_returned", sale == null ? 0 : sale.returned());
    }
    return out;
  }
}
