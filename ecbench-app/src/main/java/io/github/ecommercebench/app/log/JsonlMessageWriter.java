package io.github.ecommercebench.app.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.ToolExecutionResult;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ToolCall;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 消息轨迹 JSONL 写入器，端口 Python {@code _append_message_log}。
 *
 * <p>每行一条独立 JSON（{@code ensure_ascii=false} 等价：非 ASCII 原样输出），字段名与 Python 逐一对齐：role/content/
 * reasoning_content/ tool_calls（OpenAI 线格式，arguments 为 JSON 字符串）/tool_call_id；原始 reasoning_items
 * 被脱敏为 {@code _reasoning_items_n} 计数。 上下文裁剪以 {@code {"_event":"context_truncation",...}} 元事件记录（无
 * role，永不进入模型）。每行写入后 flush，close 幂等。
 */
public final class JsonlMessageWriter implements AutoCloseable {

    private final ObjectMapper mapper;
    private final BufferedWriter writer;
    private boolean closed;

    public JsonlMessageWriter(Path file, ObjectMapper mapper) {
        this.mapper = mapper;
        try {
            this.writer =
                    Files.newBufferedWriter(
                            file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法打开消息日志: " + file, exception);
        }
    }

    public void writeMessage(ChatMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role().wireName());
        if (message.content() == null) {
            node.putNull("content");
        } else {
            node.put("content", message.content());
        }
        if (message.reasoningContent() != null && !message.reasoningContent().isEmpty()) {
            node.put("reasoning_content", message.reasoningContent());
        }
        if (!message.reasoningItems().isEmpty()) {
            node.put("_reasoning_items_n", message.reasoningItems().size());
        }
        if (!message.toolCalls().isEmpty()) {
            node.set("tool_calls", toolCalls(message.toolCalls()));
        }
        if (message.toolCallId() != null) {
            node.put("tool_call_id", message.toolCallId());
        }
        writeLine(node);
    }

    public void writeToolResult(ToolExecutionResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", "tool");
        node.put("content", result.content() == null ? "" : result.content());
        node.put("tool_call_id", result.toolCallId());
        writeLine(node);
    }

    public void writeContextTruncation(int turn, int tokensFreed) {
        ObjectNode node = mapper.createObjectNode();
        node.put("_event", "context_truncation");
        node.put("turn", turn);
        node.put("tokens_freed", tokensFreed);
        writeLine(node);
    }

    private ArrayNode toolCalls(List<ToolCall> toolCalls) {
        ArrayNode array = mapper.createArrayNode();
        for (ToolCall call : toolCalls) {
            ObjectNode item = mapper.createObjectNode();
            item.put("id", call.id());
            item.put("type", "function");
            ObjectNode function = mapper.createObjectNode();
            function.put("name", call.name());
            function.put("arguments", call.arguments() == null ? "{}" : call.arguments().toString());
            item.set("function", function);
            array.add(item);
        }
        return array;
    }

    private void writeLine(ObjectNode node) {
        try {
            writer.write(mapper.writeValueAsString(node));
            writer.write("\n");
            writer.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException("写入消息日志失败", exception);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.close();
        } catch (IOException exception) {
            throw new UncheckedIOException("关闭消息日志失败", exception);
        }
    }
}
