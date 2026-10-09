package io.github.ecommercebench.app.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.RunJob;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ChatRole;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.llm.model.ToolDefinition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预构建 job JSONL 加载器，端口 Python {@code run.py} 中 {@code --job-file} 的逐行读取。
 *
 * <p>逐行以 Jackson 解析，空行跳过；保留 task/idx/data_source/messages/tool_schemas 与 agent_info 中的运行上限；缺失值回退到
 * {@link RunConfig} 与内置默认。非法行的异常信息包含 1 基行号，便于定位。Python job 中的 traj/database 属于运行期产物，Java agent 以
 * messages 重建轨迹，故不保留。
 */
public final class JobFileLoader {

    private static final String DEFAULT_TASK = "agent_multiturn/long_horizon/ecommerce_bench";
    private static final String DEFAULT_DATA_SOURCE = "ecommerce_bench";
    private static final int DEFAULT_MAX_TOOL_RESPONSE_CHARS = 64 * 1024;

    private final ObjectMapper mapper;
    private final RunConfig fallback;

    /** 注入 JSON mapper 与缺省回退用的 RunConfig（job 未提供上限时采用）。 */
    public JobFileLoader(ObjectMapper mapper, RunConfig fallback) {
        this.mapper = mapper;
        this.fallback = fallback;
    }

    /** 逐行读取并解析 JSONL，跳过空行；任一行解析失败即抛出含 1 基行号的异常。 */
    public List<RunJob> load(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取 job 文件: " + file, exception);
        }
        List<RunJob> jobs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                jobs.add(parse(mapper.readTree(line)));
            } catch (IOException | RuntimeException exception) {
                throw new IllegalArgumentException(
                        "job 文件第 " + (i + 1) + " 行解析失败: " + exception.getMessage(), exception);
            }
        }
        return jobs;
    }

    /** 将单行 JSON 转为 RunJob：运行上限取 agent_info 字段并回退到 RunConfig，缺失的 task/data_source 用内置默认。 */
    private RunJob parse(JsonNode root) {
        JsonNode agentInfo = root.path("agent_info");
        int maxTurns = agentInfo.path("max_turn").asInt(fallback.maxTurns());
        int maxDays = agentInfo.path("max_day").asInt(fallback.maxDays());
        int maxTokenCapacity = agentInfo.path("max_token_capacity").asInt(fallback.maxTokenCapacity());
        int maxToolResponseChars =
                agentInfo.path("max_tool_response_chars").asInt(DEFAULT_MAX_TOOL_RESPONSE_CHARS);
        return new RunJob(
                root.path("task").asText(DEFAULT_TASK),
                root.path("idx").asInt(0),
                root.path("data_source").asText(DEFAULT_DATA_SOURCE),
                parseMessages(root.path("messages")),
                parseToolSchemas(root.path("tool_schemas")),
                maxTurns,
                maxDays,
                maxTokenCapacity,
                maxToolResponseChars);
    }

    /** 解析 messages 数组为 ChatMessage 列表（含 tool_calls/reasoning 等字段），非数组返回空。 */
    private List<ChatMessage> parseMessages(JsonNode node) {
        List<ChatMessage> messages = new ArrayList<>();
        if (!node.isArray()) {
            return messages;
        }
        for (JsonNode element : node) {
            messages.add(
                    new ChatMessage(
                            role(element.path("role").asText("user")),
                            element.hasNonNull("content") ? element.get("content").asText() : null,
                            parseToolCalls(element.path("tool_calls")),
                            element.hasNonNull("tool_call_id") ? element.get("tool_call_id").asText() : null,
                            element.hasNonNull("reasoning_content")
                                    ? element.get("reasoning_content").asText()
                                    : null,
                            List.of(),
                            false,
                            Map.of()));
        }
        return messages;
    }

    /** 解析 assistant 消息的 tool_calls 数组，逐个抽取 function 名与参数。 */
    private List<ToolCall> parseToolCalls(JsonNode node) {
        List<ToolCall> toolCalls = new ArrayList<>();
        if (!node.isArray()) {
            return toolCalls;
        }
        for (JsonNode element : node) {
            JsonNode function = element.path("function");
            toolCalls.add(
                    new ToolCall(
                            element.path("id").asText(null),
                            function.path("name").asText(""),
                            parseArguments(function.path("arguments")),
                            null));
        }
        return toolCalls;
    }

    /** 归一化工具参数：缺失/空返回空对象，字符串则尝试再解析为 JSON，否则原样返回。 */
    private JsonNode parseArguments(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return mapper.createObjectNode();
        }
        if (node.isTextual()) {
            try {
                return mapper.readTree(node.asText());
            } catch (IOException exception) {
                return mapper.createObjectNode();
            }
        }
        return node;
    }

    /** 解析 tool_schemas，兼容 function 包裹与顶层两种写法，参数兼容 parameters/input_schema。 */
    private List<ToolDefinition> parseToolSchemas(JsonNode node) {
        List<ToolDefinition> schemas = new ArrayList<>();
        if (!node.isArray()) {
            return schemas;
        }
        for (JsonNode element : node) {
            JsonNode function = element.has("function") ? element.path("function") : element;
            JsonNode parameters =
                    function.has("parameters") ? function.get("parameters") : function.path("input_schema");
            schemas.add(
                    new ToolDefinition(
                            function.path("name").asText(""),
                            function.path("description").asText(""),
                            parameters));
        }
        return schemas;
    }

    /** 将线格式角色名映射为 ChatRole，未知一律回退 user。 */
    private static ChatRole role(String wire) {
        return switch (wire) {
            case "system" -> ChatRole.SYSTEM;
            case "assistant" -> ChatRole.ASSISTANT;
            case "tool" -> ChatRole.TOOL;
            default -> ChatRole.USER;
        };
    }
}
