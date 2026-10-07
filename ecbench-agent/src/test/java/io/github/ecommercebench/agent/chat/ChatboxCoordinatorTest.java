package io.github.ecommercebench.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.chat.ConversationStore;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.kernel.KernelManager;
import io.github.ecommercebench.opponent.metrics.NegotiationTracker;
import io.github.ecommercebench.opponent.model.NegotiationState;
import io.github.ecommercebench.opponent.order.OrderProcessor;
import io.github.ecommercebench.opponent.parser.NegotiationBlockParser;
import io.github.ecommercebench.simulation.SimulationEngine;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ChatboxCoordinator 契约测试：单发/广播/破产/未知供应商/成交提交/余额不足回滚/VIP 同意。 */
class ChatboxCoordinatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path DATA =
      Path.of(System.getProperty("user.dir"), "..", "data").normalize();

  private CatalogData catalog;
  private SimulationEngine engine;
  private ConversationStore conversations;
  private KernelManager kernelManager;
  private OrderProcessor orderProcessor;
  private SimulationOrderExecutionAdapter orderPort;
  private AtomicInteger renderCalls;
  private AtomicBoolean vipConsent;
  private ChatboxCoordinator coordinator;

  private Supplier goodSupplier;
  private Product goodProduct;
  private Supplier vipSupplier;

  @BeforeEach
  void setUp() {
    catalog = new CsvCatalogLoader().load(DATA);
    RandomStreams randomStreams = new RandomStreams(42L);
    engine = new SimulationEngine(catalog, RunConfig.defaults(), randomStreams);
    conversations = new ConversationStore();
    SupplierPolicy policy = new SupplierPolicy();
    kernelManager =
        new KernelManager(catalog, policy, randomStreams, new NegotiationTracker(randomStreams));
    orderProcessor = new OrderProcessor(catalog, policy, randomStreams);
    orderPort = new SimulationOrderExecutionAdapter(engine);
    renderCalls = new AtomicInteger();
    vipConsent = new AtomicBoolean(false);
    SupplierReplyRenderer renderer =
        request -> {
          renderCalls.incrementAndGet();
          return "Thanks for your message.\n\nBest regards,\n" + request.supplier().supplierName();
        };
    coordinator =
        new ChatboxCoordinator(
            catalog,
            engine,
            conversations,
            new NegotiationBlockParser(MAPPER),
            kernelManager,
            orderProcessor,
            orderPort,
            renderer,
            message -> vipConsent.get(),
            MAPPER);

    goodSupplier =
        catalog.suppliers().stream()
            .filter(supplier -> "good".equals(supplier.supplierType()))
            .findFirst()
            .orElseThrow();
    goodProduct =
        catalog.products().stream()
            .filter(
                product ->
                    goodSupplier.categoriesServed().contains(product.category())
                        && !product.category().isBlank())
            .min(Comparator.comparing(Product::referencePrice))
            .orElseThrow();
    vipSupplier =
        catalog.suppliers().stream()
            .filter(supplier -> "vip_fee".equals(supplier.fraudType()))
            .findFirst()
            .orElseThrow();
  }

  private String offerBlock(String sku, BigDecimal price, int quantity) {
    return "Hello\n```negotiate\n{\"action\":\"offer\",\"sku_id\":\""
        + sku
        + "\",\"price\":"
        + price.toPlainString()
        + ",\"quantity\":"
        + quantity
        + "}\n```";
  }

  @Test
  void emptyTargetsReturnsNoUidProvided() {
    ObjectNode out = coordinator.send(List.of(), "hi", 0);

    assertThat(out.get("error").asText()).isEqualTo("no_uid_provided");
    assertThat(out.has("current_time")).isTrue();
  }

  @Test
  void unknownSupplierReturnsError() {
    ObjectNode out = coordinator.send(List.of("nobody@wholesale.com"), "hi", 0);

    assertThat(out.get("error").asText()).isEqualTo("supplier_not_found");
  }

  @Test
  void singlePlainMessageReturnsMessageSent() {
    ObjectNode out = coordinator.send(List.of(goodSupplier.supplierEmail()), "hello", 0);

    assertThat(out.get("message").asText()).isEqualTo("message_sent");
    assertThat(out.get("supplier_reply").asText()).contains(goodSupplier.supplierName());
    assertThat(out.has("current_time")).isTrue();
    assertThat(renderCalls.get()).isEqualTo(1);
  }

  @Test
  void broadcastReturnsPerSupplierResponses() {
    Supplier second =
        catalog.suppliers().stream()
            .filter(supplier -> !supplier.supplierEmail().equals(goodSupplier.supplierEmail()))
            .findFirst()
            .orElseThrow();

    ObjectNode out =
        coordinator.send(
            List.of(goodSupplier.supplierEmail(), second.supplierEmail()), "hi there", 0);

    assertThat(out.get("message").asText()).isEqualTo("broadcast_sent");
    assertThat(out.get("recipients").asInt()).isEqualTo(2);
    assertThat(out.get("responses").size()).isEqualTo(2);
    assertThat(out.get("responses").get(0).get("uid").asText())
        .isEqualTo(goodSupplier.supplierEmail());
    assertThat(out.get("responses").get(0).get("message").asText()).isEqualTo("message_sent");
  }

  @Test
  void bankruptSupplierSkipsRenderer() {
    conversations.markBankrupt(goodSupplier.supplierName());

    ObjectNode out = coordinator.send(List.of(goodSupplier.supplierEmail()), "hi", 0);

    assertThat(out.get("message").asText()).isEqualTo("supplier_bankrupt");
    assertThat(out.get("supplier_reply").asText()).contains("ceased");
    assertThat(renderCalls.get()).isEqualTo(0);
  }

  @Test
  void conversationHistoryIncludedWhenRequested() {
    ObjectNode out = coordinator.send(List.of(goodSupplier.supplierEmail()), "hi", 5);

    assertThat(out.get("conversation_history").isArray()).isTrue();
    assertThat(out.get("conversation_history").get(0).get("from").asText()).isEqualTo("You");
    assertThat(out.get("conversation_history").get(0).get("content").asText()).isEqualTo("hi");
  }

  @Test
  void acceptedOfferCommitsAgreementAndConfirmsOrder() {
    BigDecimal highPrice = goodProduct.referencePrice().multiply(BigDecimal.TEN);

    ObjectNode out =
        coordinator.send(
            List.of(goodSupplier.supplierEmail()),
            offerBlock(goodProduct.productId(), highPrice, 1),
            0);

    assertThat(out.get("negotiation_responses").get(0).get("decision").asText())
        .isEqualTo("Accept");
    assertThat(out.get("order_confirmed").asBoolean()).isTrue();
    assertThat(out.get("orders_placed").get(0).get("sku_id").asText())
        .isEqualTo(goodProduct.productId());
    assertThat(
            kernelManager
                .state(goodSupplier.supplierName(), goodProduct.productId())
                .equals(NegotiationState.COMPLETED))
        .isTrue();
    assertThat(orderPort.bankBalance().amount().doubleValue()).isLessThan(100000.0);
  }

  @Test
  void insufficientFundsRollsBackAgreement() {
    BigDecimal highPrice = goodProduct.referencePrice().multiply(BigDecimal.TEN);

    ObjectNode out =
        coordinator.send(
            List.of(goodSupplier.supplierEmail()),
            offerBlock(goodProduct.productId(), highPrice, 10_000_000),
            0);

    assertThat(out.get("order_failed").asBoolean()).isTrue();
    assertThat(out.get("failed_orders").get(0).get("error").asText())
        .contains("Insufficient funds");
    assertThat(out.get("error").asText()).contains("Insufficient funds");
    assertThat(
            kernelManager
                .state(goodSupplier.supplierName(), goodProduct.productId())
                .equals(NegotiationState.ACTIVE))
        .isTrue();
  }

  @Test
  void vipConsentChargesMembershipFee() {
    conversations.append(
        vipSupplier.supplierName(),
        "assistant",
        "Join our VIP membership program for exclusive pricing.",
        Instant.EPOCH);
    vipConsent.set(true);
    double bankBefore = orderPort.bankBalance().amount().doubleValue();

    ObjectNode out =
        coordinator.send(
            List.of(vipSupplier.supplierEmail()), "Yes, charge the membership fee now.", 0);

    assertThat(out.get("order_confirmed").asBoolean()).isTrue();
    assertThat(out.get("orders_placed").get(0).get("sku_id").asText()).isEqualTo("MEMBERSHIP_FEE");
    assertThat(out.get("orders_placed").get(0).get("charged").asDouble()).isEqualTo(1000.0);
    assertThat(orderPort.bankBalance().amount().doubleValue()).isEqualTo(bankBefore - 1000.0);
  }
}
