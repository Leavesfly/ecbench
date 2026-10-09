package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.dto.WithdrawResult;

/**
 * withdraw：把平台钱包余额提现到银行账户，输出逐键对齐 Python `withdraw`（省略 amount 即全额提现）。
 */
public final class WithdrawTool implements EcommerceTool {

    private static final String NAME = "withdraw";

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
        Money amount = ToolArgs.money(args == null ? null : args.get("amount"), null);
        WithdrawResult result = context.engine().withdraw(amount);
        ObjectNode out = context.mapper().createObjectNode();
        if (!result.success()) {
            out.put("success", false);
            out.put("error", result.error());
            return out;
        }
        out.put("success", true);
        out.put("withdrawn", result.withdrawn().amount().doubleValue());
        out.put("bank_balance", result.bankBalance().amount().doubleValue());
        out.put("platform_wallet", result.platformWallet().amount().doubleValue());
        return out;
    }
}
