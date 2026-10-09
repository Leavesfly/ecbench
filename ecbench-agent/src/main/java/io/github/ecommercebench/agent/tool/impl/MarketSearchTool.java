package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.domain.catalog.MarketGuidance;
import io.github.ecommercebench.llm.model.ToolDefinition;

/**
 * market_search：三级渐进式市场调研，输出逐键对齐 Python `market_search`。
 */
public final class MarketSearchTool implements EcommerceTool {

    private static final String NAME = "market_search";

    private final ToolDefinition definition = ToolSchemas.load(NAME);
    private final MarketGuidance guidance;

    public MarketSearchTool(MarketGuidance guidance) {
        this.guidance = guidance;
    }

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
        MarketSearchView view =
                new MarketSearchView(
                        context.engine().catalog(),
                        guidance,
                        context.engine().state().maxStores(),
                        context.mapper());
        return view.search(storeType, category);
    }
}
