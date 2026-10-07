package io.github.ecommercebench.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.error.ToolExecutionException;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.simulation.SimulationEngine;
import io.github.ecommercebench.simulation.error.BusinessRuleException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具批次执行器，端口自 Python `EcommerceToolManager.ask_code_exec`。
 *
 * <p>按顺序执行一批工具调用，保证：名称未知、参数错误、业务冲突都转成稳定的错误 JSON 而不抛给 Agent 主循环； {@code wait_for_next_day}
 * 在同批内只推进一天（首个执行推进，重复者收到提示）。由于 Java 采用日级推进、不存在日内时钟， 只有 {@code wait_for_next_day}
 * 会推进日期，故推进被推迟到其余工具执行完毕后统一发生， 与 Python 将 wait 结果延后填充的语义一致。
 */
public final class EcommerceToolManager {

  /** 唯一的日期推进工具名。 */
  public static final String WAIT_FOR_NEXT_DAY = "wait_for_next_day";

  private static final String DUPLICATE_WAIT_NOTE =
      "Duplicate wait_for_next_day in the same batch was ignored — time advances only "
          + "one day per turn. Issue one wait per turn.";

  private final ToolRegistry registry;
  private final SimulationEngine engine;
  private final ObjectMapper mapper;
  private final ToolExecutionContext context;

  public EcommerceToolManager(ToolRegistry registry, SimulationEngine engine, ObjectMapper mapper) {
    this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    this.engine = Objects.requireNonNull(engine, "engine 不能为空");
    this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    this.context = new ToolExecutionContext(engine, mapper);
  }

  /** 注册表中全部工具的 schema，供构建发往模型的请求使用。 */
  public List<io.github.ecommercebench.llm.model.ToolDefinition> definitions() {
    return registry.definitions();
  }

  /** 顺序执行一批工具调用，返回与入参一一对应、按序排列的结果。 */
  public List<ToolExecutionResult> execute(List<ToolCall> calls) {
    int count = calls.size();
    String[] responses = new String[count];
    boolean critical = false;
    String detail = "";
    int waitIndex = -1;
    JsonNode waitArgs = null;
    boolean waitSeen = false;

    for (int i = 0; i < count; i++) {
      ToolCall call = calls.get(i);
      String name = call.name();
      if (WAIT_FOR_NEXT_DAY.equals(name)) {
        if (!waitSeen) {
          waitSeen = true;
          waitIndex = i;
          waitArgs = call.arguments();
        } else {
          responses[i] = textJson("note", DUPLICATE_WAIT_NOTE);
        }
        continue;
      }
      Optional<EcommerceTool> tool = registry.find(name);
      if (tool.isEmpty()) {
        responses[i] = textJson("error", "Unknown tool: " + name);
        continue;
      }
      try {
        ObjectNode out = tool.get().execute(call.arguments(), context);
        injectCurrentTime(out);
        responses[i] = write(out);
      } catch (IllegalArgumentException | BusinessRuleException e) {
        responses[i] = textJson("error", "Invalid arguments for " + name + ": " + e.getMessage());
      } catch (RuntimeException e) {
        critical = true;
        detail = String.valueOf(e.getMessage());
        responses[i] = textJson("error", "Tool execution exception: " + e.getMessage());
        break;
      }
    }

    if (!critical && waitIndex >= 0) {
      responses[waitIndex] = runDeferredWait(waitArgs);
    }

    if (critical) {
      String failed = failureJson(detail);
      for (int i = 0; i < count; i++) {
        if (responses[i] == null) {
          responses[i] = failed;
        }
      }
    }
    for (int i = 0; i < count; i++) {
      if (responses[i] == null) {
        responses[i] = textJson("current_time", currentTime());
      }
    }

    List<ToolExecutionResult> results = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      results.add(new ToolExecutionResult(calls.get(i).id(), calls.get(i).name(), responses[i]));
    }
    return results;
  }

  /** 延后执行唯一的日期推进；已终止则回填带原因的状态负载。 */
  private String runDeferredWait(JsonNode waitArgs) {
    if (engine.state().terminated()) {
      ObjectNode out = mapper.createObjectNode();
      out.put("current_time", currentTime());
      out.put("note", "Simulation has ended; no further days to advance.");
      String reason = engine.state().terminationReason();
      if (reason != null) {
        out.put("termination_reason", reason);
      }
      return write(out);
    }
    Optional<EcommerceTool> waitTool = registry.find(WAIT_FOR_NEXT_DAY);
    if (waitTool.isEmpty()) {
      return textJson("current_time", currentTime());
    }
    try {
      ObjectNode out = waitTool.get().execute(waitArgs, context);
      injectCurrentTime(out);
      return write(out);
    } catch (IllegalArgumentException | BusinessRuleException e) {
      ObjectNode out = mapper.createObjectNode();
      out.put("success", false);
      out.put("error", String.valueOf(e.getMessage()));
      return write(out);
    } catch (RuntimeException e) {
      return textJson("error", "Tool execution exception: " + e.getMessage());
    }
  }

  private void injectCurrentTime(ObjectNode out) {
    out.put("current_time", currentTime());
  }

  /** 日级模型下当日营业开始时间固定为 08:00，保持与 Python `%Y-%m-%d %H:%M` 线格式兼容。 */
  private String currentTime() {
    return engine.currentDate() + " 08:00";
  }

  private String textJson(String key, String value) {
    ObjectNode node = mapper.createObjectNode();
    node.put(key, value);
    return write(node);
  }

  private String failureJson(String detail) {
    ObjectNode node = mapper.createObjectNode();
    node.put("error", "Tool execution failed");
    node.put("detail", detail);
    return write(node);
  }

  private String write(ObjectNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new ToolExecutionException("工具结果序列化失败", e);
    }
  }
}
