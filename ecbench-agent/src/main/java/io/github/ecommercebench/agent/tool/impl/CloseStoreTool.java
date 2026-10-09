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
            "Reopening this store type later will cost the setup fee again and start reputation from "
                    + "scratch.";
    private static final String RETURN_NOTE =
            " Inventory was moved to your warehouse and WILL keep incurring storage fees until sold or "
                    + "liquidated (close with liquidate=true to sell it back at salvage value instead).";

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
                        " Inventory was liquidated at %.0f%% of purchase cost (¥%.2f credited); it no "
                                + "longer incurs storage.",
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
