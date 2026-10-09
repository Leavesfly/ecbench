package io.github.ecommercebench.agent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.chat.ChatboxCoordinator;
import io.github.ecommercebench.agent.chat.SimulationOrderExecutionAdapter;
import io.github.ecommercebench.agent.chat.SupplierReplyRenderer;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.chat.ConversationStore;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.kernel.KernelManager;
import io.github.ecommercebench.opponent.metrics.NegotiationTracker;
import io.github.ecommercebench.opponent.order.OrderProcessor;
import io.github.ecommercebench.opponent.parser.NegotiationBlockParser;
import io.github.ecommercebench.simulation.SimulationEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * chatbox 工具契约测试：Schema 对齐 golden、uid/uids 归一化、单发/广播委派。
 */
class ChatboxToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DATA =
            Path.of(System.getProperty("user.dir"), "..", "data").normalize();
    private static final Path SCHEMA_DIR =
            Path.of(System.getProperty("user.dir"), "src", "test", "resources", "tool-schema");

    private SimulationEngine engine;
    private ChatboxTool tool;
    private Supplier goodSupplier;

    @BeforeEach
    void setUp() {
        CatalogData catalog = new CsvCatalogLoader().load(DATA);
        RandomStreams randomStreams = new RandomStreams(42L);
        engine = new SimulationEngine(catalog, RunConfig.defaults(), randomStreams);
        SupplierPolicy policy = new SupplierPolicy();
        KernelManager kernelManager =
                new KernelManager(catalog, policy, randomStreams, new NegotiationTracker(randomStreams));
        OrderProcessor orderProcessor = new OrderProcessor(catalog, policy, randomStreams);
        SupplierReplyRenderer renderer = request -> "Reply from " + request.supplier().supplierName();
        ChatboxCoordinator coordinator =
                new ChatboxCoordinator(
                        catalog,
                        engine,
                        new ConversationStore(),
                        new NegotiationBlockParser(MAPPER),
                        kernelManager,
                        orderProcessor,
                        new SimulationOrderExecutionAdapter(engine),
                        renderer,
                        message -> false,
                        MAPPER);
        tool = new ChatboxTool(coordinator);
        goodSupplier =
                catalog.suppliers().stream()
                        .filter(supplier -> "good".equals(supplier.supplierType()))
                        .findFirst()
                        .orElseThrow();
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(engine, MAPPER);
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode node(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void schemaMatchesPythonGolden() throws Exception {
        JsonNode golden =
                MAPPER.readTree(Files.readString(SCHEMA_DIR.resolve("chatbox.json"))).path("function");
        assertThat(tool.name()).isEqualTo(golden.path("name").asText());
        assertThat(tool.definition().description()).isEqualTo(golden.path("description").asText());
        assertThat(tool.definition().inputSchema()).isEqualTo(golden.path("parameters"));
    }

    @Test
    void normalizeUidsHandlesSingleListAndDelimited() {
        assertThat(ChatboxTool.normalizeUids(node("\"a@x.com\""), null)).containsExactly("a@x.com");
        assertThat(ChatboxTool.normalizeUids(null, node("[\"a@x.com\",\"b@y.com\"]")))
                .containsExactly("a@x.com", "b@y.com");
        assertThat(ChatboxTool.normalizeUids(node("\"a@x.com, b@y.com\""), null))
                .containsExactly("a@x.com", "b@y.com");
        assertThat(ChatboxTool.normalizeUids(node("\"a@x.com\""), node("[\"a@x.com\",\"b@y.com\"]")))
                .containsExactly("a@x.com", "b@y.com");
        assertThat(ChatboxTool.normalizeUids(null, null)).isEmpty();
    }

    @Test
    void executeDelegatesSingleRecipient() {
        ObjectNode out =
                tool.execute(
                        json("{\"uid\":\"" + goodSupplier.supplierEmail() + "\",\"content\":\"hello\"}"),
                        ctx());

        assertThat(out.get("message").asText()).isEqualTo("message_sent");
        assertThat(out.get("supplier_reply").asText()).contains(goodSupplier.supplierName());
    }

    @Test
    void executeWithoutRecipientReportsNoUid() {
        ObjectNode out = tool.execute(json("{\"content\":\"hello\"}"), ctx());

        assertThat(out.get("error").asText()).isEqualTo("no_uid_provided");
    }

    @Test
    void executeBroadcastsToUidsArray() {
        Supplier second =
                engine.catalog().suppliers().stream()
                        .filter(supplier -> !supplier.supplierEmail().equals(goodSupplier.supplierEmail()))
                        .findFirst()
                        .orElseThrow();

        ObjectNode out =
                tool.execute(
                        json(
                                "{\"uids\":[\""
                                        + goodSupplier.supplierEmail()
                                        + "\",\""
                                        + second.supplierEmail()
                                        + "\"],\"content\":\"hi\"}"),
                        ctx());

        assertThat(out.get("message").asText()).isEqualTo("broadcast_sent");
        assertThat(out.get("recipients").asInt()).isEqualTo(2);
        assertThat(List.of(out.get("responses").get(0).get("uid").asText()))
                .containsExactly(goodSupplier.supplierEmail());
    }
}
