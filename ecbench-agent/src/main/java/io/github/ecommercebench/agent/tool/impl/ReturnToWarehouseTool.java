package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.ItemOperationResult;
import io.github.ecommercebench.simulation.dto.QtyItem;
import io.github.ecommercebench.simulation.dto.ReturnResult;
import io.github.ecommercebench.simulation.error.BusinessRuleException;
import java.util.ArrayList;
import java.util.List;

/** return_to_warehouse：把店铺货架库存退回仓库池，输出逐键对齐 Python `return_to_warehouse`。 */
public final class ReturnToWarehouseTool implements EcommerceTool {

  private static final String NAME = "return_to_warehouse";

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
    JsonNode items = args.get("items");
    if (items == null || !items.isArray()) {
      throw new IllegalArgumentException("'items'");
    }
    List<QtyItem> requested = new ArrayList<>();
    for (JsonNode node : items) {
      requested.add(
          new QtyItem(node.path("product_id").asText(""), ToolArgs.intOr(node.get("quantity"), 0)));
    }
    ObjectNode out = context.mapper().createObjectNode();
    ReturnResult result;
    try {
      result = context.engine().returnToWarehouse(storeId, requested);
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
        node.put("returned", item.quantity());
      } else {
        node.put("error", item.error());
      }
    }
    return out;
  }
}
