package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.chat.ChatboxCoordinator;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * chatbox：向供应商发消息（单发 {@code uid} 或广播 {@code uids}），委派 {@link ChatboxCoordinator}。
 */
public final class ChatboxTool implements EcommerceTool {

    private static final String NAME = "chatbox";

    private final ToolDefinition definition = ToolSchemas.load(NAME);
    private final ChatboxCoordinator coordinator;

    public ChatboxTool(ChatboxCoordinator coordinator) {
        this.coordinator = coordinator;
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
        List<String> targets =
                normalizeUids(
                        args == null ? null : args.get("uid"), args == null ? null : args.get("uids"));
        String content = ToolArgs.string(args, "content", "");
        int historyCount = ToolArgs.intOr(args == null ? null : args.get("history_count"), 0);
        return coordinator.send(targets, content, historyCount);
    }

    /**
     * 端口 Python `_normalize_uids`：先 uids 后 uid，支持数组或以逗号/空白分隔的字符串，按首次出现顺序去重。
     */
    static List<String> normalizeUids(JsonNode uid, JsonNode uids) {
        List<String> raw = new ArrayList<>();
        collect(uids, raw);
        collect(uid, raw);
        return new ArrayList<>(new LinkedHashSet<>(raw));
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, out);
            }
            return;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.contains(",") || value.contains(" ")) {
            for (String part : value.split("[,\\s]+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        } else {
            out.add(value);
        }
    }
}
