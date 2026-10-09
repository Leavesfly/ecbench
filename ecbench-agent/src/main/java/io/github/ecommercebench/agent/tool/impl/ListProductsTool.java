package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.ProductView;

import java.util.List;

/**
 * list_products：可按店型/类别过滤的商品目录，逐键对齐 Python `list_products`（标题截断 80）。
 */
public final class ListProductsTool implements EcommerceTool {

    private static final String NAME = "list_products";

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
        String storeType = ToolArgs.string(args, "store_type");
        String category = ToolArgs.string(args, "category");
        List<ProductView> views = context.engine().listProducts(storeType, category);
        ObjectNode out = context.mapper().createObjectNode();
        ArrayNode results = out.putArray("results");
        for (ProductView view : views) {
            ObjectNode row = results.addObject();
            row.put("product_id", view.productId());
            row.put("title", ToolArgs.truncate(view.title(), 80));
            row.put("category", view.category());
            row.put("brand", view.brand());
            row.put("size", view.size());
            row.put("reference_price", view.referencePrice().doubleValue());
        }
        out.put("total", views.size());
        return out;
    }
}
