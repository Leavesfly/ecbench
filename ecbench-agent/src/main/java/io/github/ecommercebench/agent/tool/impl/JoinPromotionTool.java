package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.PromotionJoinResult;

import java.math.BigDecimal;

/**
 * join_promotion：让店铺参加平台促销活动，输出逐键对齐 Python `join_promotion`。
 */
public final class JoinPromotionTool implements EcommerceTool {

    private static final String NAME = "join_promotion";

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
        String eventName = ToolArgs.require(args, "event_name");
        BigDecimal discountRate = ToolArgs.decimal(args.get("discount_rate"), null);
        if (discountRate == null) {
            throw new IllegalArgumentException("'discount_rate'");
        }
        PromotionJoinResult result = context.engine().joinPromotion(storeId, eventName, discountRate);
        ObjectNode out = context.mapper().createObjectNode();
        if (!result.success()) {
            out.put("success", false);
            out.put("error", result.error());
            return out;
        }
        out.put("success", true);
        out.put("store_id", result.storeId());
        out.put("event", result.eventName());
        out.put("discount_rate", result.discountRate().doubleValue());
        out.put("active_now", result.activeNow());
        out.put("max_demand_multiplier", result.maxDemandMultiplier().doubleValue());
        return out;
    }
}
