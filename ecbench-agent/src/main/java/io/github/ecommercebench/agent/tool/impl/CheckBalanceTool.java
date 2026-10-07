package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.dto.BalanceView;
import java.util.Map;

/** check_balance：三账户余额、未发货销售额与近期结算计划，逐键对齐 Python `get_balance`。 */
public final class CheckBalanceTool implements EcommerceTool {

  private static final String NAME = "check_balance";

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
    BalanceView balance = context.engine().checkBalance();
    ObjectNode out = context.mapper().createObjectNode();
    out.put("bank_balance", balance.bankBalance().amount().doubleValue());
    out.put("platform_wallet", balance.platformWallet().amount().doubleValue());
    out.put("pending_settlement", balance.pendingSettlement().amount().doubleValue());
    out.put("unshipped_sales_value", balance.unshippedSalesValue().amount().doubleValue());
    out.put("total", balance.total().amount().doubleValue());
    ObjectNode upcoming = out.putObject("upcoming_settlements");
    balance.upcomingSettlements().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .limit(5)
        .forEach(
            entry ->
                upcoming.put(entry.getKey().toString(), entry.getValue().amount().doubleValue()));
    out.put("day", balance.day());
    out.put("date", balance.date().toString());
    out.put(
        "note",
        "Sales revenue first enters 'pending_settlement' (escrow) and becomes withdrawable from "
            + "the wallet "
            + EconomicRules.SETTLEMENT_WINDOW_DAYS
            + " days after you SHIP the order. Refunds net against escrow before it settles. Ship "
            + "orders promptly (ship_orders) or they cancel.");
    return out;
  }
}
