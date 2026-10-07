package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.OpenStoreResult;

/** open_store：开设新店，输出逐键对齐 Python `open_store`（含 is_reopen / reopen_note）。 */
public final class OpenStoreTool implements EcommerceTool {

  private static final String NAME = "open_store";
  private static final String REOPEN_NOTE =
      "This store type was opened before. The setup fee was charged again and reputation must be "
          + "rebuilt from scratch — frequent open/close churn is costly.";

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
    String storeType = ToolArgs.require(args, "store_type");
    String storeName = ToolArgs.require(args, "store_name");
    OpenStoreResult result = context.engine().openStore(storeType, storeName);
    ObjectNode out = context.mapper().createObjectNode();
    if (!result.success()) {
      out.put("success", false);
      out.put("error", result.error());
      return out;
    }
    out.put("success", true);
    out.put("store_id", result.storeId());
    out.put("store_type", result.storeType());
    out.put("store_name", result.storeName());
    out.put("setup_fee_charged", result.setupFeeCharged().amount().doubleValue());
    out.put("daily_ops_cost", result.dailyOpsCost().amount().doubleValue());
    out.put("is_reopen", result.reopen());
    out.put("reopen_note", result.reopen() ? REOPEN_NOTE : "");
    out.put("bank_balance", result.bankBalance().amount().doubleValue());
    ArrayNode categories = out.putArray("allowed_categories");
    result.allowedCategories().forEach(categories::add);
    return out;
  }
}
