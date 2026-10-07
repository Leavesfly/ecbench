package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.SupplierView;
import java.util.List;

/** supplier_search：按商品名/类别/店型检索供应商，逐键对齐 Python `supplier_search`（{note,results,count}）。 */
public final class SupplierSearchTool implements EcommerceTool {

  private static final String NAME = "supplier_search";
  private static final String NOTE =
      "Suppliers are listed in random order (not ranked). Contact as many as possible to compare "
          + "prices before ordering.";

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
    String productName = ToolArgs.string(args, "product_name");
    String category = ToolArgs.string(args, "category");
    String storeType = ToolArgs.string(args, "store_type");
    List<SupplierView> views = context.engine().supplierSearch(productName, category, storeType);
    ObjectNode out = context.mapper().createObjectNode();
    out.put("note", NOTE);
    ArrayNode results = out.putArray("results");
    for (SupplierView view : views) {
      ObjectNode row = results.addObject();
      row.put("supplier_name", view.supplierName());
      row.put("supplier_email", view.supplierEmail());
      ArrayNode categories = row.putArray("categories_served");
      view.categoriesServed().forEach(categories::add);
    }
    out.put("count", views.size());
    return out;
  }
}
