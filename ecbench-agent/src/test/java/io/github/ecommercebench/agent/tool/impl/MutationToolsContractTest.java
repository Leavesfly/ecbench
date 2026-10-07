package io.github.ecommercebench.agent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.SimulationEngine;
import io.github.ecommercebench.simulation.dto.PublishItem;
import io.github.ecommercebench.simulation.error.BusinessRuleException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 九个变更型工具的契约测试：Schema 与 Python golden 逐键一致，输出字段对齐 Python。 */
class MutationToolsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path DATA =
      Path.of(System.getProperty("user.dir"), "..", "data").normalize();
  private static final Path SCHEMA_DIR =
      Path.of(System.getProperty("user.dir"), "src", "test", "resources", "tool-schema");

  private CatalogData catalog;
  private SimulationEngine engine;
  private String beautyProductId;

  @BeforeEach
  void setUp() {
    catalog = new CsvCatalogLoader().load(DATA);
    engine = new SimulationEngine(catalog, RunConfig.defaults(), new RandomStreams(42L));
    List<String> allowed = catalog.storeTypes().get("beauty").allowedCategories();
    beautyProductId =
        catalog.products().stream()
            .filter(product -> allowed.contains(product.category()))
            .map(product -> product.productId())
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

  private void assertSchemaMatchesGolden(EcommerceTool tool) throws Exception {
    JsonNode golden =
        MAPPER
            .readTree(Files.readString(SCHEMA_DIR.resolve(tool.name() + ".json")))
            .path("function");
    assertThat(tool.name()).isEqualTo(golden.path("name").asText());
    assertThat(tool.definition().description()).isEqualTo(golden.path("description").asText());
    assertThat(tool.definition().inputSchema()).isEqualTo(golden.path("parameters"));
  }

  /** 开一家 beauty 店并上架 qty 件商品（进货价 ¥20、零售价 ¥50），返回 storeId。 */
  private String openBeautyAndStock(int qty) {
    String storeId = engine.openStore("beauty", "My Beauty").storeId();
    engine.receivePurchaseOrder("Supplier A", beautyProductId, qty, Money.of("20"), false, 0);
    engine.publishToStore(storeId, List.of(new PublishItem(beautyProductId, qty, Money.of("50"))));
    return storeId;
  }

  @Test
  void allMutationToolSchemasMatchPythonGolden() throws Exception {
    List<EcommerceTool> tools =
        List.of(
            new OpenStoreTool(),
            new CloseStoreTool(),
            new StockStoreTool(),
            new SetPricesTool(),
            new ReturnToWarehouseTool(),
            new JoinPromotionTool(),
            new WithdrawTool(),
            new ShipOrdersTool(),
            new WaitForNextDayTool());
    for (EcommerceTool tool : tools) {
      assertSchemaMatchesGolden(tool);
    }
  }

  @Test
  void openStoreEmitsSetupFeeCategoriesAndReopenFlag() {
    ObjectNode first =
        new OpenStoreTool()
            .execute(json("{\"store_type\":\"beauty\",\"store_name\":\"My Beauty\"}"), ctx());

    assertThat(first.get("success").asBoolean()).isTrue();
    assertThat(first.has("store_id")).isTrue();
    assertThat(first.get("store_type").asText()).isEqualTo("beauty");
    assertThat(first.get("store_name").asText()).isEqualTo("My Beauty");
    assertThat(first.get("setup_fee_charged").asDouble()).isGreaterThan(0.0);
    assertThat(first.has("daily_ops_cost")).isTrue();
    assertThat(first.get("is_reopen").asBoolean()).isFalse();
    assertThat(first.get("reopen_note").asText()).isEmpty();
    assertThat(first.get("bank_balance").asDouble()).isLessThan(100000.0);
    assertThat(first.get("allowed_categories").isArray()).isTrue();

    String storeId = first.get("store_id").asText();
    engine.closeStore(storeId, false);
    ObjectNode reopen =
        new OpenStoreTool()
            .execute(json("{\"store_type\":\"beauty\",\"store_name\":\"Again\"}"), ctx());
    assertThat(reopen.get("is_reopen").asBoolean()).isTrue();
    assertThat(reopen.get("reopen_note").asText()).isNotEmpty();
  }

  @Test
  void openStoreReportsFailureForDuplicateType() {
    new OpenStoreTool().execute(json("{\"store_type\":\"beauty\",\"store_name\":\"One\"}"), ctx());

    ObjectNode duplicate =
        new OpenStoreTool()
            .execute(json("{\"store_type\":\"beauty\",\"store_name\":\"Two\"}"), ctx());

    assertThat(duplicate.get("success").asBoolean()).isFalse();
    assertThat(duplicate.has("error")).isTrue();
  }

  @Test
  void closeStoreReturnsInventoryWhenNotLiquidating() {
    String storeId = openBeautyAndStock(4);

    ObjectNode out =
        new CloseStoreTool().execute(json("{\"store_id\":\"" + storeId + "\"}"), ctx());

    assertThat(out.get("success").asBoolean()).isTrue();
    assertThat(out.get("store_id").asText()).isEqualTo(storeId);
    assertThat(out.has("liquidated")).isFalse();
    assertThat(out.get("inventory_returned_to_warehouse").get(beautyProductId).asInt())
        .isEqualTo(4);
    assertThat(out.get("total_items_returned").asInt()).isEqualTo(4);
    assertThat(out.get("note").asText()).contains("warehouse");
  }

  @Test
  void closeStoreLiquidatesInventoryWhenRequested() {
    String storeId = openBeautyAndStock(4);

    ObjectNode out =
        new CloseStoreTool()
            .execute(json("{\"store_id\":\"" + storeId + "\",\"liquidate\":true}"), ctx());

    assertThat(out.get("success").asBoolean()).isTrue();
    assertThat(out.get("liquidated").asBoolean()).isTrue();
    assertThat(out.get("items_liquidated").get(beautyProductId).get("quantity").asInt())
        .isEqualTo(4);
    assertThat(out.get("items_liquidated").get(beautyProductId).has("salvage")).isTrue();
    assertThat(out.get("total_units_liquidated").asInt()).isEqualTo(4);
    assertThat(out.get("salvage_credited").asDouble()).isGreaterThan(0.0);
    assertThat(out.has("salvage_rate")).isTrue();
    assertThat(out.has("bank_balance")).isTrue();
    assertThat(out.get("note").asText()).contains("liquidated");
  }

  @Test
  void publishSetPricesAndReturnEmitResultItems() {
    String storeId = engine.openStore("beauty", "My Beauty").storeId();
    engine.receivePurchaseOrder("Supplier A", beautyProductId, 10, Money.of("20"), false, 0);

    ObjectNode published =
        new StockStoreTool()
            .execute(
                json(
                    "{\"store_id\":\""
                        + storeId
                        + "\",\"plan\":[{\"product_id\":\""
                        + beautyProductId
                        + "\",\"quantity\":4,\"retail_price\":50}]}"),
                ctx());
    assertThat(published.get("store_id").asText()).isEqualTo(storeId);
    JsonNode publishedItem = published.get("results").get(0);
    assertThat(publishedItem.get("success").asBoolean()).isTrue();
    assertThat(publishedItem.get("quantity_stocked").asInt()).isEqualTo(4);
    assertThat(publishedItem.get("retail_price").asDouble()).isEqualTo(50.0);

    ObjectNode repriced =
        new SetPricesTool()
            .execute(
                json(
                    "{\"store_id\":\""
                        + storeId
                        + "\",\"prices\":[{\"product_id\":\""
                        + beautyProductId
                        + "\",\"price\":60}]}"),
                ctx());
    JsonNode repricedItem = repriced.get("results").get(0);
    assertThat(repricedItem.get("success").asBoolean()).isTrue();
    assertThat(repricedItem.get("old_price").asDouble()).isEqualTo(50.0);
    assertThat(repricedItem.get("new_price").asDouble()).isEqualTo(60.0);

    ObjectNode returned =
        new ReturnToWarehouseTool()
            .execute(
                json(
                    "{\"store_id\":\""
                        + storeId
                        + "\",\"items\":[{\"product_id\":\""
                        + beautyProductId
                        + "\",\"quantity\":2}]}"),
                ctx());
    JsonNode returnedItem = returned.get("results").get(0);
    assertThat(returnedItem.get("success").asBoolean()).isTrue();
    assertThat(returnedItem.get("returned").asInt()).isEqualTo(2);
  }

  @Test
  void joinPromotionSucceedsForActiveEventAndRejectsBadDiscount() {
    String storeId = engine.openStore("beauty", "My Beauty").storeId();
    JoinPromotionTool tool = new JoinPromotionTool();

    ObjectNode joined =
        tool.execute(
            json(
                "{\"store_id\":\""
                    + storeId
                    + "\",\"event_name\":\"New Year Kickoff Sale\",\"discount_rate\":0.2}"),
            ctx());
    assertThat(joined.get("success").asBoolean()).isTrue();
    assertThat(joined.get("store_id").asText()).isEqualTo(storeId);
    assertThat(joined.get("event").asText()).isEqualTo("New Year Kickoff Sale");
    assertThat(joined.get("discount_rate").asDouble()).isEqualTo(0.2);
    assertThat(joined.get("active_now").asBoolean()).isTrue();
    assertThat(joined.get("max_demand_multiplier").asDouble()).isEqualTo(2.0);

    ObjectNode badDiscount =
        tool.execute(
            json(
                "{\"store_id\":\""
                    + storeId
                    + "\",\"event_name\":\"New Year Kickoff Sale\",\"discount_rate\":0.9}"),
            ctx());
    assertThat(badDiscount.get("success").asBoolean()).isFalse();
    assertThat(badDiscount.has("error")).isTrue();
  }

  @Test
  void withdrawFailsWhenWalletEmpty() {
    ObjectNode out = new WithdrawTool().execute(json("{}"), ctx());

    assertThat(out.get("success").asBoolean()).isFalse();
    assertThat(out.has("error")).isTrue();
  }

  @Test
  void shipOrdersListsEmptyQueueAndRejectsBadSpeed() {
    ShipOrdersTool tool = new ShipOrdersTool();

    ObjectNode listed = tool.execute(json("{\"action\":\"list\"}"), ctx());
    assertThat(listed.get("pending_shipments").isArray()).isTrue();
    assertThat(listed.get("pending_shipments").size()).isEqualTo(0);
    assertThat(listed.get("count").asInt()).isEqualTo(0);
    assertThat(listed.has("note")).isTrue();

    ObjectNode badSpeed = tool.execute(json("{\"action\":\"ship\",\"speed\":\"teleport\"}"), ctx());
    assertThat(badSpeed.get("success").asBoolean()).isFalse();
    assertThat(badSpeed.get("error").asText()).contains("Invalid speed");
  }

  @Test
  void waitForNextDayAdvancesDateAndRejectsStaleDay() {
    WaitForNextDayTool tool = new WaitForNextDayTool();

    ObjectNode out = tool.execute(json("{\"current_day\":\"2026-01-01\"}"), ctx());
    assertThat(out.get("date").asText()).isEqualTo("2026-01-02");
    assertThat(out.get("day").asInt()).isEqualTo(1);
    assertThat(out.get("current_time").asText()).isEqualTo("2026-01-02 08:00");

    assertThatThrownBy(() -> tool.execute(json("{\"current_day\":\"2026-01-01\"}"), ctx()))
        .isInstanceOf(BusinessRuleException.class);
  }
}
