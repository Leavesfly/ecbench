package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.memory.Memo;
import io.github.ecommercebench.agent.memory.MemoryStore;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;

import java.util.List;
import java.util.Optional;

/**
 * operate_memory：run-local 备忘录增删改查，输出逐键对齐 Python `tools/operate_memory.py`。
 *
 * <p>备忘录在上下文裁剪后仍可读取，最多 {@value #MAX_MEMO_COUNT} 条。所有分支返回 Python 同名字段与同文案的错误信息。
 */
public final class OperateMemoryTool implements EcommerceTool {

    private static final String NAME = "operate_memory";
    private static final int MAX_MEMO_COUNT = 20;
    private static final String ACTION_HINT = "Use 'add', 'get', 'update', 'delete', or 'list'.";

    private final ToolDefinition definition = ToolSchemas.load(NAME);
    private final MemoryStore store;

    public OperateMemoryTool(MemoryStore store) {
        this.store = store;
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
        ObjectMapper mapper = context.mapper();
        String action = ToolArgs.string(args, "action");
        if (isMissing(action)) {
            return error(mapper, "'action' is required. " + ACTION_HINT);
        }
        String title = ToolArgs.string(args, "title");
        String content = ToolArgs.string(args, "content");
        switch (action) {
            case "add":
                return add(mapper, title, content);
            case "update":
                return update(mapper, title, content);
            case "delete":
                return delete(mapper, title);
            case "get":
                return get(mapper, title);
            case "list":
                return listAll(mapper);
            default:
                return error(mapper, "Unknown action '" + action + "'. " + ACTION_HINT);
        }
    }

    private ObjectNode add(ObjectMapper mapper, String title, String content) {
        if (isMissing(title) || isMissing(content)) {
            return error(mapper, "Both 'title' and 'content' are required for add.");
        }
        if (store.get(title).isPresent()) {
            return error(
                    mapper, "Memo with title '" + title + "' already exists. Use 'update' to modify it.");
        }
        if (memoCount() >= MAX_MEMO_COUNT) {
            return error(
                    mapper,
                    "Memo limit reached (" + MAX_MEMO_COUNT + "). Delete a memo before adding a new one.");
        }
        store.add(title, content);
        ObjectNode out = mapper.createObjectNode();
        out.put("success", true);
        out.put("message", "Memo '" + title + "' added.");
        out.put("total_memos", memoCount());
        return out;
    }

    private ObjectNode update(ObjectMapper mapper, String title, String content) {
        if (isMissing(title) || isMissing(content)) {
            return error(mapper, "Both 'title' and 'content' are required for update.");
        }
        if (store.get(title).isEmpty()) {
            return error(mapper, "Memo with title '" + title + "' not found. Use 'add' to create it.");
        }
        store.update(title, content);
        ObjectNode out = mapper.createObjectNode();
        out.put("success", true);
        out.put("message", "Memo '" + title + "' updated.");
        return out;
    }

    private ObjectNode delete(ObjectMapper mapper, String title) {
        if (isMissing(title)) {
            return error(mapper, "'title' is required for delete.");
        }
        if (!store.delete(title)) {
            return error(mapper, "Memo with title '" + title + "' not found.");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("success", true);
        out.put("message", "Memo '" + title + "' deleted.");
        out.put("total_memos", memoCount());
        return out;
    }

    private ObjectNode get(ObjectMapper mapper, String title) {
        if (isMissing(title)) {
            return listAll(mapper);
        }
        Optional<Memo> memo = store.get(title);
        if (memo.isEmpty()) {
            ObjectNode out = mapper.createObjectNode();
            out.put("error", "Memo with title '" + title + "' not found.");
            ArrayNode titles = out.putArray("titles");
            allMemos().forEach(item -> titles.add(item.title()));
            return out;
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("title", title);
        out.put("content", memo.get().content());
        return out;
    }

    private ObjectNode listAll(ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode memos = out.putObject("memos");
        List<Memo> all = allMemos();
        all.forEach(item -> memos.put(item.title(), item.content()));
        out.put("total_memos", all.size());
        return out;
    }

    private List<Memo> allMemos() {
        return store.list(MAX_MEMO_COUNT);
    }

    private int memoCount() {
        return allMemos().size();
    }

    private ObjectNode error(ObjectMapper mapper, String message) {
        ObjectNode out = mapper.createObjectNode();
        out.put("error", message);
        return out;
    }

    /**
     * 对齐 Python 的 `not value` 语义：null 或空串视为缺失（空白串仍算有值）。
     */
    private static boolean isMissing(String value) {
        return value == null || value.isEmpty();
    }
}
