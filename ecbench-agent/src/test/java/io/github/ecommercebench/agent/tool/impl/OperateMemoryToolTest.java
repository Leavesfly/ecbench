package io.github.ecommercebench.agent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.memory.InMemoryMemoryStore;
import io.github.ecommercebench.agent.memory.MemoryStore;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * operate_memory 契约测试：五种 action 的输出逐键对齐 Python `tools/operate_memory.py`。
 */
class OperateMemoryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SCHEMA_DIR =
            Path.of(System.getProperty("user.dir"), "src", "test", "resources", "tool-schema");

    private MemoryStore store;
    private OperateMemoryTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        tool = new OperateMemoryTool(store);
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(null, MAPPER);
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void schemaMatchesPythonGolden() throws Exception {
        JsonNode golden =
                MAPPER
                        .readTree(Files.readString(SCHEMA_DIR.resolve("operate_memory.json")))
                        .path("function");
        assertThat(tool.name()).isEqualTo(golden.path("name").asText());
        assertThat(tool.definition().description()).isEqualTo(golden.path("description").asText());
        assertThat(tool.definition().inputSchema()).isEqualTo(golden.path("parameters"));
    }

    @Test
    void addGetUpdateDeleteListFollowPythonContract() {
        ObjectNode added =
                tool.execute(
                        json("{\"action\":\"add\",\"title\":\"supplier\",\"content\":\"cheap\"}"), ctx());
        assertThat(added.get("success").asBoolean()).isTrue();
        assertThat(added.get("message").asText()).isEqualTo("Memo 'supplier' added.");
        assertThat(added.get("total_memos").asInt()).isEqualTo(1);

        ObjectNode got = tool.execute(json("{\"action\":\"get\",\"title\":\"supplier\"}"), ctx());
        assertThat(got.get("title").asText()).isEqualTo("supplier");
        assertThat(got.get("content").asText()).isEqualTo("cheap");

        ObjectNode updated =
                tool.execute(
                        json("{\"action\":\"update\",\"title\":\"supplier\",\"content\":\"pricey\"}"), ctx());
        assertThat(updated.get("success").asBoolean()).isTrue();
        assertThat(updated.get("message").asText()).isEqualTo("Memo 'supplier' updated.");

        ObjectNode all = tool.execute(json("{\"action\":\"get\"}"), ctx());
        assertThat(all.get("memos").get("supplier").asText()).isEqualTo("pricey");
        assertThat(all.get("total_memos").asInt()).isEqualTo(1);

        ObjectNode listed = tool.execute(json("{\"action\":\"list\"}"), ctx());
        assertThat(listed.get("memos").isObject()).isTrue();
        assertThat(listed.get("total_memos").asInt()).isEqualTo(1);

        ObjectNode deleted =
                tool.execute(json("{\"action\":\"delete\",\"title\":\"supplier\"}"), ctx());
        assertThat(deleted.get("success").asBoolean()).isTrue();
        assertThat(deleted.get("message").asText()).isEqualTo("Memo 'supplier' deleted.");
        assertThat(deleted.get("total_memos").asInt()).isEqualTo(0);
    }

    @Test
    void errorPathsMatchPythonMessages() {
        assertThat(tool.execute(json("{}"), ctx()).get("error").asText())
                .contains("'action' is required");

        assertThat(
                tool.execute(json("{\"action\":\"add\",\"title\":\"t\"}"), ctx()).get("error").asText())
                .contains("Both 'title' and 'content' are required for add");

        assertThat(
                tool.execute(
                                json("{\"action\":\"update\",\"title\":\"nope\",\"content\":\"x\"}"), ctx())
                        .get("error")
                        .asText())
                .contains("not found. Use 'add'");

        assertThat(tool.execute(json("{\"action\":\"delete\"}"), ctx()).get("error").asText())
                .contains("'title' is required for delete");

        assertThat(
                tool.execute(json("{\"action\":\"delete\",\"title\":\"nope\"}"), ctx())
                        .get("error")
                        .asText())
                .isEqualTo("Memo with title 'nope' not found.");

        ObjectNode getMissing = tool.execute(json("{\"action\":\"get\",\"title\":\"nope\"}"), ctx());
        assertThat(getMissing.get("error").asText()).contains("not found");
        assertThat(getMissing.get("titles").isArray()).isTrue();

        assertThat(tool.execute(json("{\"action\":\"frobnicate\"}"), ctx()).get("error").asText())
                .contains("Unknown action 'frobnicate'");
    }

    @Test
    void addRejectsDuplicateTitleAndBeyondTwentyLimit() {
        tool.execute(json("{\"action\":\"add\",\"title\":\"supplier\",\"content\":\"cheap\"}"), ctx());
        assertThat(
                tool.execute(
                                json("{\"action\":\"add\",\"title\":\"supplier\",\"content\":\"x\"}"), ctx())
                        .get("error")
                        .asText())
                .contains("already exists");

        for (int i = 1; i < 20; i++) {
            tool.execute(json("{\"action\":\"add\",\"title\":\"t" + i + "\",\"content\":\"c\"}"), ctx());
        }
        ObjectNode overflow =
                tool.execute(json("{\"action\":\"add\",\"title\":\"t20\",\"content\":\"c\"}"), ctx());
        assertThat(overflow.get("error").asText()).contains("Memo limit reached (20)");
    }
}
