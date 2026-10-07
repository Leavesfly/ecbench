package io.github.ecommercebench.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.context.ContextEditor;
import io.github.ecommercebench.agent.context.TokenCounter;
import io.github.ecommercebench.agent.tool.EcommerceToolManager;
import io.github.ecommercebench.agent.tool.ToolRegistry;
import io.github.ecommercebench.agent.tool.impl.CheckBalanceTool;
import io.github.ecommercebench.agent.tool.impl.WaitForNextDayTool;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ChatRole;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.simulation.SimulationEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 主循环契约测试：消息顺序、观察者事件、日期推进、轮数与各类终止原因、上下文裁剪。 */
class EcommerceBenchAgentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path DATA =
      Path.of(System.getProperty("user.dir"), "..", "data").normalize();
  private static final ContextConfig NO_TRUNCATION = new ContextConfig(1_000_000, 10, 2);
  private static final ContextConfig FORCE_TRUNCATION = new ContextConfig(10, 5, 0);

  private static CatalogData loadCatalog() {
    return new CsvCatalogLoader().load(DATA);
  }

  private static RunConfig runConfig(int maxTurns, int maxDays) {
    return new RunConfig(
        null,
        16_384,
        maxTurns,
        maxDays,
        Money.of("100000"),
        Money.of("50"),
        128_000,
        null,
        DATA,
        null,
        null,
        1,
        42L);
  }

  private static LlmResponse toolCall(String id, String name, String argsJson) {
    JsonNode args;
    try {
      args = MAPPER.readTree(argsJson);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return new LlmResponse("", List.of(new ToolCall(id, name, args, null)), null, List.of(), null);
  }

  private static LlmResponse noTool() {
    return new LlmResponse("All done.", List.of(), null, List.of(), null);
  }

  /** 记录观察者事件，供断言工具调用序列、裁剪次数与最终结果。 */
  private static final class RecordingObserver implements RunObserver {
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private int truncations;
    private RunResult completed;

    @Override
    public void onAssistantMessage(ChatMessage message) {
      toolCalls.addAll(message.toolCalls());
    }

    @Override
    public void onContextTruncation(int turn, int tokensFreed) {
      truncations++;
    }

    @Override
    public void onRunComplete(RunResult result) {
      completed = result;
    }

    List<String> toolNames() {
      return toolCalls.stream().map(ToolCall::name).toList();
    }
  }

  private EcommerceBenchAgent agent(
      LlmClient llm,
      RunObserver observer,
      RunConfig config,
      ContextConfig contextConfig,
      SimulationEngine engine) {
    ToolRegistry registry =
        new ToolRegistry(List.of(new CheckBalanceTool(), new WaitForNextDayTool()));
    EcommerceToolManager manager = new EcommerceToolManager(registry, engine, MAPPER);
    TokenCounter counter = message -> message.content() == null ? 0 : message.content().length();
    ContextEditor editor = new ContextEditor(counter);
    return new EcommerceBenchAgent(
        llm, "test-model", manager, editor, counter, engine, observer, config, contextConfig);
  }

  @Test
  void runsToolsThenTerminatesOnRepeatedNoToolCalls() {
    CatalogData catalog = loadCatalog();
    SimulationEngine engine =
        new SimulationEngine(catalog, runConfig(4000, 365), new RandomStreams(42L));
    RecordingObserver observer = new RecordingObserver();
    LlmClient scripted =
        new LlmClient() {
          private int call;

          @Override
          public LlmResponse generate(LlmRequest request) {
            call++;
            if (call == 1) {
              return toolCall("c1", "check_balance", "{}");
            }
            if (call == 2) {
              return toolCall("c2", "wait_for_next_day", "{\"current_day\":\"2026-01-01\"}");
            }
            return noTool();
          }
        };

    RunResult result =
        agent(scripted, observer, runConfig(4000, 365), NO_TRUNCATION, engine).run(null);

    assertThat(result.terminationReason()).isEqualTo(TerminationReason.NO_TOOL_CALLS);
    assertThat(result.canonicalReason()).isEqualTo("env_terminated");
    assertThat(observer.toolNames()).containsExactly("check_balance", "wait_for_next_day");
    assertThat(engine.currentDate().toString()).isEqualTo("2026-01-02");
    assertThat(result.finalDate()).isEqualTo("2026-01-02");
    assertThat(result.finalDay()).isEqualTo(1);
    assertThat(result.turns()).isEqualTo(5);
    assertThat(result.messages().get(0).role()).isEqualTo(ChatRole.SYSTEM);
    assertThat(result.messages().get(1).role()).isEqualTo(ChatRole.USER);
    assertThat(result.messages().get(2).role()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(result.messages().get(3).role()).isEqualTo(ChatRole.TOOL);
    assertThat(observer.completed).isSameAs(result);
  }

  @Test
  void terminatesOnMaxTurns() {
    CatalogData catalog = loadCatalog();
    RunConfig config = runConfig(2, 365);
    SimulationEngine engine = new SimulationEngine(catalog, config, new RandomStreams(42L));
    RecordingObserver observer = new RecordingObserver();
    LlmClient alwaysBalance = request -> toolCall("c", "check_balance", "{}");

    RunResult result = agent(alwaysBalance, observer, config, NO_TRUNCATION, engine).run(null);

    assertThat(result.terminationReason()).isEqualTo(TerminationReason.MAX_TURNS_REACHED);
    assertThat(result.turns()).isEqualTo(2);
  }

  @Test
  void terminatesWhenEngineReachesMaxDays() {
    CatalogData catalog = loadCatalog();
    RunConfig config = runConfig(4000, 1);
    SimulationEngine engine = new SimulationEngine(catalog, config, new RandomStreams(42L));
    RecordingObserver observer = new RecordingObserver();
    LlmClient alwaysWait =
        request ->
            toolCall(
                "w", "wait_for_next_day", "{\"current_day\":\"" + engine.currentDate() + "\"}");

    RunResult result = agent(alwaysWait, observer, config, NO_TRUNCATION, engine).run(null);

    assertThat(result.terminationReason()).isEqualTo(TerminationReason.ENV_COMPLETED);
    assertThat(result.canonicalReason()).isEqualTo("env_completed");
    assertThat(engine.state().terminated()).isTrue();
  }

  @Test
  void reportsContextTruncationToObserver() {
    CatalogData catalog = loadCatalog();
    SimulationEngine engine =
        new SimulationEngine(catalog, runConfig(4000, 365), new RandomStreams(42L));
    RecordingObserver observer = new RecordingObserver();
    LlmClient scripted =
        new LlmClient() {
          private int call;

          @Override
          public LlmResponse generate(LlmRequest request) {
            call++;
            if (call == 1) {
              return toolCall("c1", "check_balance", "{}");
            }
            if (call == 2) {
              return toolCall("c2", "wait_for_next_day", "{\"current_day\":\"2026-01-01\"}");
            }
            return noTool();
          }
        };

    RunResult result =
        agent(scripted, observer, runConfig(4000, 365), FORCE_TRUNCATION, engine).run(null);

    assertThat(observer.truncations).isGreaterThanOrEqualTo(1);
    assertThat(result.contextClearCount()).isGreaterThanOrEqualTo(1);
    assertThat(result.contextTokensFreedTotal()).isGreaterThan(0);
  }

  @Test
  void defaultJobCarriesRenderedSystemPromptAndSeedUserMessage() {
    CatalogData catalog = loadCatalog();
    SimulationEngine engine =
        new SimulationEngine(catalog, runConfig(4000, 365), new RandomStreams(42L));
    EcommerceBenchAgent agent =
        agent(
            request -> noTool(),
            new RecordingObserver(),
            runConfig(4000, 365),
            NO_TRUNCATION,
            engine);

    RunJob job = agent.defaultJob();

    assertThat(job.initialMessages()).hasSize(2);
    assertThat(job.initialMessages().get(0).role()).isEqualTo(ChatRole.SYSTEM);
    assertThat(job.initialMessages().get(1).role()).isEqualTo(ChatRole.USER);
    assertThat(job.initialMessages().get(1).content())
        .isEqualTo("You are running an e-commerce business.");
    String system = job.initialMessages().get(0).content();
    assertThat(system).contains("You are an assistant");
    assertThat(system).contains("Wang Wang");
    assertThat(system).contains("365 days");
    assertThat(system).contains("¥100000");
    assertThat(system).contains("128000 tokens");
    assertThat(job.toolSchemas()).isNotEmpty();
    assertThat(job.dataSource()).isEqualTo("ecommerce_bench");
  }

  @Test
  void llmFailureTerminatesAndNotifiesObserver() {
    CatalogData catalog = loadCatalog();
    SimulationEngine engine =
        new SimulationEngine(catalog, runConfig(4000, 365), new RandomStreams(42L));
    RecordingObserver observer = new RecordingObserver();
    LlmClient failing =
        request -> {
          throw new IllegalStateException("provider down");
        };

    RunResult result =
        agent(failing, observer, runConfig(4000, 365), NO_TRUNCATION, engine).run(null);

    assertThat(result.terminationReason()).isEqualTo(TerminationReason.LLM_ERROR);
    assertThat(result.terminationDetail()).contains("llm_error");
    assertThat(result.turns()).isEqualTo(1);
  }
}
