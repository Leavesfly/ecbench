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
import io.github.ecommercebench.simulation.dto.PriceItem;
import io.github.ecommercebench.simulation.dto.SetPricesResult;
import io.github.ecommercebench.simulation.error.BusinessRuleException;

import java.util.ArrayList;
import java.util.List;

/**
 * set_prices：批量调整店铺商品零售价，输出逐键对齐 Python `set_prices`。
 */
public final class SetPricesTool implements EcommerceTool {

    private static final String NAME = "set_prices";

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
        JsonNode prices = args.get("prices");
        if (prices == null || !prices.isArray()) {
            throw new IllegalArgumentException("'prices'");
        }
        List<PriceItem> items = new ArrayList<>();
        for (JsonNode node : prices) {
            items.add(
                    new PriceItem(
                            node.path("product_id").asText(""), ToolArgs.money(node.get("price"), Money.ZERO)));
        }
        ObjectNode out = context.mapper().createObjectNode();
        SetPricesResult result;
        try {
            result = context.engine().setPrices(storeId, items);
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
                node.put("old_price", item.oldPrice().amount().doubleValue());
                node.put("new_price", item.newPrice().amount().doubleValue());
            } else {
                node.put("error", item.error());
            }
        }
        return out;
    }
}
