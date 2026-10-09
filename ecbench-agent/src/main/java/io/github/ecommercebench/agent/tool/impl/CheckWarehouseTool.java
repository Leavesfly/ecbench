package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.WarehouseRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * check_warehouse：仓库池化库存，逐键对齐 Python `get_warehouse`（items 为按 SKU 索引的字典）。
 */
public final class CheckWarehouseTool implements EcommerceTool {

    private static final String NAME = "check_warehouse";

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
        List<WarehouseRow> rows = context.engine().checkWarehouse();
        ObjectNode items = context.mapper().createObjectNode();
        int totalItems = 0;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (WarehouseRow row : rows) {
            int quantity = row.quantity();
            totalItems += quantity;
            totalValue =
                    totalValue.add(row.purchasePrice().amount().multiply(BigDecimal.valueOf(quantity)));
            ObjectNode item = items.putObject(row.productId());
            item.put("quantity", quantity);
            item.put("product", ToolArgs.truncate(row.product(), 60));
            item.put("category", row.category());
            item.put("purchase_price", row.purchasePrice().amount().doubleValue());
            item.put("size", row.size());
        }
        ObjectNode out = context.mapper().createObjectNode();
        out.put("total_items", totalItems);
        out.put("total_value", totalValue.setScale(2, RoundingMode.HALF_UP).doubleValue());
        out.set("items", items);
        return out;
    }
}
