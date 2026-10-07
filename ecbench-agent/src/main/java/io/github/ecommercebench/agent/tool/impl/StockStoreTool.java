package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.ItemOperationResult;
import io.github.ecommercebench.simulation.dto.PublishItem;
import io.github.ecommercebench.simulation.dto.PublishResult;
import io.github.ecommercebench.simulation.error.BusinessRuleException;
import java.util.ArrayList;
import java.util.List;

/** publish_to_store：把仓库库存上架到店铺页面，输出逐键对齐 Python `publish_to_store`。 */
public final class StockStoreTool implements EcommerceTool {

  private static final String NAME = "publish_to_store";

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
    String storeId = ToolArgs.require(args, "store_id");
    JsonNode plan = args.get("plan");
    if (plan == null || !plan.isArray()) {
      throw new IllegalArgumentException("'plan'");
    }
    List<PublishItem> items = new ArrayList<>();
    for (JsonNode node : plan) {
      items.add(
          new PublishItem(
              node.path("product_id").asText(""),
              ToolArgs.intOr(node.get("quantity"), 0),
              ToolArgs.money(node.get("retail_price"), Money.ZERO)));
    }
    ObjectNode out = context.mapper().createObjectNode();
    PublishResult result;
    try {
      result = context.engine().publishToStore(storeId, items);
    } catch (BusinessRuleException e) {
      out.put("success", false);
      out.put("error", e.getMessage());
      return out;
    }
    out.put("store_id", result.storeId());
    ArrayNode results = out.putArray("results");
    for (ItemOperationResult item : result.results()) {
      ObjectNode node = results.addObject();
      node.put("product_id", item.productId());
      node.put("success", item.success());
      if (item.success()) {
        node.put("quantity_stocked", item.quantity());
        node.put("retail_price", item.newPrice().amount().doubleValue());
      } else {
        node.put("error", item.error());
      }
    }
    return out;
  }
}
