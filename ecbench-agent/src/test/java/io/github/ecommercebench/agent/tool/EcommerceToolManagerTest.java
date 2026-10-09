package io.github.ecommercebench.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.error.ToolExecutionException;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.SimulationEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 校验工具管理器的顺序执行、wait 去重与稳定错误契约。
 */
class EcommerceToolManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SimulationEngine newEngine() {
        CatalogData catalog =
                new CatalogData(List.of(), List.of(), Map.of(), Map.of(), List.of(), List.of());
        return new SimulationEngine(catalog, RunConfig.defaults(), new RandomStreams(42L));
    }

    private EcommerceToolManager manager(SimulationEngine engine, List<EcommerceTool> tools) {
        return new EcommerceToolManager(new ToolRegistry(tools), engine, mapper);
    }

    private ToolCall call(String id, String name, String argsJson) throws Exception {
        return new ToolCall(id, name, mapper.readTree(argsJson), null);
    }

    @Test
    void executesCallsInOrderAndPreservesIds() throws Exception {
        List<String> log = new ArrayList<>();
        EcommerceToolManager manager =
                manager(
                        newEngine(),
                        List.of(new RecordingTool("alpha", log, null), new RecordingTool("beta", log, null)));

        List<ToolExecutionResult> results =
                manager.execute(List.of(call("1", "alpha", "{}"), call("2", "beta", "{}")));

        assertThat(log).containsExactly("alpha", "beta");
        assertThat(results).extracting(ToolExecutionResult::toolCallId).containsExactly("1", "2");
        assertThat(results).extracting(ToolExecutionResult::toolName).containsExactly("alpha", "beta");
        assertThat(results.get(0).content()).contains("current_time");
    }

    @Test
    void advancesOnlyOnceForDuplicateWaitInSameBatch() throws Exception {
        CountingWaitTool wait = new CountingWaitTool();
        EcommerceToolManager manager = manager(newEngine(), List.of(wait));

        List<ToolExecutionResult> results =
                manager.execute(
                        List.of(
                                call("1", "wait_for_next_day", "{\"current_day\":\"2026-01-01\"}"),
                                call("2", "wait_for_next_day", "{\"current_day\":\"2026-01-01\"}")));

        assertThat(wait.calls).isEqualTo(1);
        assertThat(results.get(0).content()).contains("\"day\"");
        assertThat(results.get(1).content()).contains("Duplicate wait_for_next_day");
    }

    @Test
    void unknownToolReturnsStableErrorWithoutThrowing() throws Exception {
        EcommerceToolManager manager = manager(newEngine(), List.of());

        List<ToolExecutionResult> results = manager.execute(List.of(call("1", "nope", "{}")));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).contains("Unknown tool: nope");
    }

    @Test
    void invalidArgumentsBecomeStableErrorAndBatchContinues() throws Exception {
        List<String> log = new ArrayList<>();
        EcommerceToolManager manager =
                manager(
                        newEngine(),
                        List.of(
                                new RecordingTool("bad", log, new IllegalArgumentException("missing store_id")),
                                new RecordingTool("good", log, null)));

        List<ToolExecutionResult> results =
                manager.execute(List.of(call("1", "bad", "{}"), call("2", "good", "{}")));

        assertThat(results.get(0).content()).contains("Invalid arguments for bad");
        assertThat(log).containsExactly("bad", "good");
    }

    @Test
    void criticalToolFailureStopsBatchAndFillsUnfilledSlots() throws Exception {
        List<String> log = new ArrayList<>();
        EcommerceToolManager manager =
                manager(
                        newEngine(),
                        List.of(
                                new RecordingTool("boom", log, new ToolExecutionException("invariant broken")),
                                new RecordingTool("after", log, null),
                                new CountingWaitTool()));

        List<ToolExecutionResult> results =
                manager.execute(
                        List.of(
                                call("1", "boom", "{}"),
                                call("2", "after", "{}"),
                                call("3", "wait_for_next_day", "{\"current_day\":\"2026-01-01\"}")));

        assertThat(log).containsExactly("boom");
        assertThat(results.get(0).content()).contains("Tool execution exception");
        assertThat(results.get(1).content()).contains("Tool execution failed");
        assertThat(results.get(2).content()).contains("Tool execution failed");
    }

    /**
     * 记录调用顺序的假工具，可选在 execute 时抛出指定异常。
     */
    private static final class RecordingTool implements EcommerceTool {
        private final String name;
        private final List<String> log;
        private final RuntimeException error;

        RecordingTool(String name, List<String> log, RuntimeException error) {
            this.name = name;
            this.log = log;
            this.error = error;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, name, JsonNodeFactory.instance.objectNode());
        }

        @Override
        public ObjectNode execute(JsonNode args, ToolExecutionContext context) {
            log.add(name);
            if (error != null) {
                throw error;
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("ok", true);
            out.put("tool", name);
            return out;
        }
    }

    /**
     * 统计推进次数的假 wait_for_next_day 工具。
     */
    private static final class CountingWaitTool implements EcommerceTool {
        private int calls = 0;

        @Override
        public String name() {
            return "wait_for_next_day";
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name(), "wait", JsonNodeFactory.instance.objectNode());
        }

        @Override
        public ObjectNode execute(JsonNode args, ToolExecutionContext context) {
            calls++;
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("day", 2);
            return out;
        }
    }
}
