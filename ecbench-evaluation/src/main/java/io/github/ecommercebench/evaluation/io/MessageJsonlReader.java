package io.github.ecommercebench.evaluation.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息 JSONL 读取器，端口 Python {@code plot_daily_balance._jsonl_assistant_turns_and_tool_calls} 与 {@code
 * _read_context_clear_count}。
 *
 * <p>逐行解析独立 JSON：统计 assistant 轮数（role==assistant）、工具调用总数（assistant 的 tool_calls 数组长度）与上下文裁剪次数
 * （{@code _event==context_truncation}）。未知字段被忽略以支持向前兼容；坏行抛出的异常信息包含文件名与 1 基行号。
 */
public final class MessageJsonlReader {

    private final ObjectMapper mapper;

    public MessageJsonlReader() {
        this(new ObjectMapper());
    }

    public MessageJsonlReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解析结果：全部消息节点与三项计数。
     */
    public record Result(
            List<JsonNode> messages, int assistantTurns, int toolCalls, int contextTruncations) {
    }

    public Result read(Path jsonl) {
        List<String> lines;
        try {
            lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取消息 JSONL: " + jsonl, exception);
        }
        List<JsonNode> messages = new ArrayList<>();
        int assistantTurns = 0;
        int toolCalls = 0;
        int truncations = 0;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).trim();
            if (raw.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = mapper.readTree(raw);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "消息 JSONL 解析失败: "
                                + jsonl.getFileName()
                                + " 第 "
                                + (i + 1)
                                + " 行: "
                                + exception.getMessage(),
                        exception);
            }
            messages.add(node);
            if ("context_truncation".equals(node.path("_event").asText(""))) {
                truncations++;
            }
            if ("assistant".equals(node.path("role").asText(""))) {
                assistantTurns++;
                JsonNode calls = node.path("tool_calls");
                if (calls.isArray()) {
                    toolCalls += calls.size();
                }
            }
        }
        return new Result(messages, assistantTurns, toolCalls, truncations);
    }
}
