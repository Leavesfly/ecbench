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

  public JobFileLoader(ObjectMapper mapper, RunConfig fallback) {
    this.mapper = mapper;
    this.fallback = fallback;
  }

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

  private static ChatRole role(String wire) {
    return switch (wire) {
      case "system" -> ChatRole.SYSTEM;
      case "assistant" -> ChatRole.ASSISTANT;
      case "tool" -> ChatRole.TOOL;
      default -> ChatRole.USER;
    };
  }
}
