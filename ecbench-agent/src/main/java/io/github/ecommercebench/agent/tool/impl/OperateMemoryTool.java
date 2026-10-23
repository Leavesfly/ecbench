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
 * <p>备忘录在上下文裁剪后仍可读取，最多 {@value #MAX_MEMO_COUNT} 条。所有分支返回与 Python 同名字段；错误与提示文案已中文化， 属有意偏离逐字端口。
 */
public final class OperateMemoryTool implements EcommerceTool {

    private static final String NAME = "operate_memory";
    private static final int MAX_MEMO_COUNT = 20;
    private static final String ACTION_HINT = "请使用 'add'、'get'、'update'、'delete' 或 'list'。";

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
            return error(mapper, "'action' 必填。 " + ACTION_HINT);
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
                return error(mapper, "未知的 action '" + action + "'。 " + ACTION_HINT);
        }
    }

    private ObjectNode add(ObjectMapper mapper, String title, String content) {
        if (isMissing(title) || isMissing(content)) {
            return error(mapper, "add 需要同时提供 'title' 与 'content'。");
        }
        if (store.get(title).isPresent()) {
            return error(
                    mapper, "标题为 '" + title + "' 的备忘录已存在，请用 'update' 修改。");
        }
        if (memoCount() >= MAX_MEMO_COUNT) {
            return error(
                    mapper,
                    "备忘录数量已达上限（" + MAX_MEMO_COUNT + "），请先删除一条再新增。");
        }
        store.add(title, content);
        ObjectNode out = mapper.createObjectNode();
        out.put("success", true);
        out.put("message", "已新增备忘录 '" + title + "'。");
        out.put("total_memos", memoCount());
        return out;
    }

    private ObjectNode update(ObjectMapper mapper, String title, String content) {
        if (isMissing(title) || isMissing(content)) {
            return error(mapper, "update 需要同时提供 'title' 与 'content'。");
        }
        if (store.get(title).isEmpty()) {
            return error(mapper, "未找到标题为 '" + title + "' 的备忘录，请用 'add' 创建。");
        }
        store.update(title, content);
        ObjectNode out = mapper.createObjectNode();
        out.put("success", true);
        out.put("message", "已更新备忘录 '" + title + "'。");
        return out;
    }

    private ObjectNode delete(ObjectMapper mapper, String title) {
        if (isMissing(title)) {
            return error(mapper, "delete 需要提供 'title'。");
        }
        if (!store.delete(title)) {
            return error(mapper, "未找到标题为 '" + title + "' 的备忘录。");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("success", true);
        out.put("message", "已删除备忘录 '" + title + "'。");
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
            out.put("error", "未找到标题为 '" + title + "' 的备忘录。");
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
