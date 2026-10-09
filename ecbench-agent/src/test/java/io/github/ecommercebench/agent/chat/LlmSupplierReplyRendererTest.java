package io.github.ecommercebench.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.llm.model.ChatRole;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.opponent.chat.ConversationStore;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * LlmSupplierReplyRenderer 测试：系统提示词构造、confirm_order 代码块剥离、空/异常回退。
 */
class LlmSupplierReplyRendererTest {

    private static final Path DATA =
            Path.of(System.getProperty("user.dir"), "..", "data").normalize();

    private static SupplierReplyRenderer.Request requestFor(Supplier supplier) {
        return new SupplierReplyRenderer.Request(
                supplier,
                "wangwang@ecbench.com",
                "Do you have stock?",
                List.of(),
                List.of(),
                "2026-01-01 08:00");
    }

    private static Supplier firstGoodSupplier(CatalogData catalog) {
        return catalog.suppliers().stream()
                .filter(supplier -> "good".equals(supplier.supplierType()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void buildsSystemPromptWithSupplierNameAndCatalog() {
        CatalogData catalog = new CsvCatalogLoader().load(DATA);
        Supplier supplier = firstGoodSupplier(catalog);
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        LlmClient client =
                request -> {
                    captured.set(request);
                    return new LlmResponse(
                            "Hello! Yes we have stock.\n\nBest regards,\n" + supplier.supplierName(),
                            List.of(),
                            null,
                            List.of(),
                            null);
                };
        LlmSupplierReplyRenderer renderer =
                new LlmSupplierReplyRenderer(catalog, new ConversationStore(), client, "npc-model");

        String reply = renderer.render(requestFor(supplier));

        assertThat(reply).contains("Yes we have stock");
        LlmRequest sent = captured.get();
        assertThat(sent.model()).isEqualTo("npc-model");
        assertThat(sent.messages().get(0).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(sent.messages().get(0).content()).contains(supplier.supplierName());
        assertThat(sent.messages().get(0).content()).contains("Product Catalog");
        assertThat(sent.messages().get(sent.messages().size() - 1).role()).isEqualTo(ChatRole.USER);
    }

    @Test
    void stripsConfirmOrderJsonBlock() {
        CatalogData catalog = new CsvCatalogLoader().load(DATA);
        Supplier supplier = firstGoodSupplier(catalog);
        LlmClient client =
                request ->
                        new LlmResponse(
                                "Sure thing.\n```json\n{\"action\": \"confirm_order\", \"sku\": \"x\"}\n```",
                                List.of(),
                                null,
                                List.of(),
                                null);
        LlmSupplierReplyRenderer renderer =
                new LlmSupplierReplyRenderer(catalog, new ConversationStore(), client, "npc-model");

        String reply = renderer.render(requestFor(supplier));

        assertThat(reply).contains("Sure thing.");
        assertThat(reply).doesNotContain("confirm_order");
    }

    @Test
    void fallsBackWhenLlmReturnsBlank() {
        CatalogData catalog = new CsvCatalogLoader().load(DATA);
        Supplier supplier = firstGoodSupplier(catalog);
        LlmSupplierReplyRenderer renderer =
                new LlmSupplierReplyRenderer(
                        catalog,
                        new ConversationStore(),
                        request -> new LlmResponse("   ", List.of(), null, List.of(), null),
                        "npc-model");

        assertThat(renderer.render(requestFor(supplier))).contains(supplier.supplierName());
    }

    @Test
    void fallsBackWhenLlmThrows() {
        CatalogData catalog = new CsvCatalogLoader().load(DATA);
        Supplier supplier = firstGoodSupplier(catalog);
        LlmClient throwing =
                request -> {
                    throw new RuntimeException("down");
                };
        LlmSupplierReplyRenderer renderer =
                new LlmSupplierReplyRenderer(catalog, new ConversationStore(), throwing, "npc-model");

        assertThat(renderer.render(requestFor(supplier))).contains(supplier.supplierName());
    }
}
