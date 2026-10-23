package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.dto.CloseStoreResult;
import io.github.ecommercebench.simulation.dto.LiquidatedItem;

import java.util.Locale;
import java.util.Map;

/**
 * close_store：关店并把库存退回仓库或清算，输出逐键对齐 Python `close_store`。
 */
public final class CloseStoreTool implements EcommerceTool {

    private static final String NAME = "close_store";
    private static final String BASE_NOTE =
            "日后重新开设该店铺类型需再次支付开店费，且声誉需从头重建。";
    private static final String RETURN_NOTE =
            " 库存已退回你的仓库，在卖出或清算之前会持续产生仓储费"
                    + "（改用 liquidate=true 关店可按残值变现）。";

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
        boolean liquidate = ToolArgs.bool(args, "liquidate", false);
        CloseStoreResult result = context.engine().closeStore(storeId, liquidate);
        ObjectNode out = context.mapper().createObjectNode();
        if (!result.success()) {
            out.put("success", false);
            out.put("error", result.error());
            return out;
        }
        out.put("success", true);
        out.put("store_id", result.storeId());
        if (result.liquidated()) {
            return liquidated(out, result);
        }
        return returned(out, result);
    }

    private ObjectNode liquidated(ObjectNode out, CloseStoreResult result) {
        double salvageTotal = result.salvageCredited().amount().doubleValue();
        double rate = EconomicRules.LIQUIDATION_SALVAGE_RATE.doubleValue();
        ObjectNode items = out.putObject("items_liquidated");
        int totalUnits = 0;
        for (Map.Entry<String, LiquidatedItem> entry : result.itemsLiquidated().entrySet()) {
            ObjectNode item = items.putObject(entry.getKey());
            item.put("quantity", entry.getValue().quantity());
            item.put("salvage", entry.getValue().salvage().amount().doubleValue());
            totalUnits += entry.getValue().quantity();
        }
        out.put(
                "note",
                BASE_NOTE
                        + String.format(
                        Locale.ROOT,
                        " 库存已按进价的 %.0f%% 清算（计入 ¥%.2f）；不再产生仓储费。",
                        rate * 100,
                        salvageTotal));
        out.put("liquidated", true);
        out.put("total_units_liquidated", totalUnits);
        out.put("salvage_credited", salvageTotal);
        out.put("salvage_rate", rate);
        out.put("bank_balance", result.bankBalance().amount().doubleValue());
        return out;
    }

    private ObjectNode returned(ObjectNode out, CloseStoreResult result) {
        ObjectNode items = out.putObject("inventory_returned_to_warehouse");
        int total = 0;
        for (Map.Entry<String, Integer> entry : result.inventoryReturned().entrySet()) {
            items.put(entry.getKey(), entry.getValue());
            total += entry.getValue();
        }
        out.put("note", BASE_NOTE + RETURN_NOTE);
        out.put("total_items_returned", total);
        return out;
    }
}
