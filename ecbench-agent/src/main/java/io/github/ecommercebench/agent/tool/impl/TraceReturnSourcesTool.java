package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.ReturnTrace;
import io.github.ecommercebench.simulation.dto.ReturnTraceRow;
import io.github.ecommercebench.simulation.dto.SupplierSource;

/** trace_return_sources：SKU 供应来源与已实现退货率，逐键对齐 Python `trace_return_sources`。 */
public final class TraceReturnSourcesTool implements EcommerceTool {

  private static final String NAME = "trace_return_sources";

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
    String productId = ToolArgs.string(args, "product_id");
    ReturnTrace trace = context.engine().traceReturnSources(productId);
    ObjectNode out = context.mapper().createObjectNode();
    if (trace.error() != null) {
      out.put("error", trace.error());
      return out;
    }
    ArrayNode products = out.putArray("products");
    for (ReturnTraceRow row : trace.products()) {
      ObjectNode node = products.addObject();
      node.put("product_id", row.productId());
      node.put("title", ToolArgs.truncate(row.title(), 60));
      node.put("category", row.category());
      node.put("total_units_delivered", row.totalUnitsDelivered());
      node.put("units_sold", row.unitsSold());
      node.put("units_returned", row.unitsReturned());
      node.put("realized_return_rate", row.realizedReturnRate());
      node.put("natural_baseline_return_rate", row.naturalBaselineReturnRate());
      ArrayNode sources = node.putArray("supplier_sources");
      for (SupplierSource source : row.supplierSources()) {
        ObjectNode sourceNode = sources.addObject();
        sourceNode.put("supplier", source.supplier());
        sourceNode.put("units_delivered", source.unitsDelivered());
        sourceNode.put("share", source.share());
      }
      node.put("note", row.note());
    }
    return out;
  }
}
