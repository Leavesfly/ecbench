package io.github.ecommercebench.agent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CsvCatalogLoader;
import io.github.ecommercebench.domain.catalog.MarketGuidance;
import io.github.ecommercebench.domain.catalog.StorePlaybookLoader;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.SimulationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 七个查询工具的契约测试：Schema 与 Python golden 逐键一致，输出字段对齐 Python。 */
class QueryToolsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path DATA =
      Path.of(System.getProperty("user.dir"), "..", "data").normalize();
  private static final Path SCHEMA_DIR =
      Path.of(System.getProperty("user.dir"), "src", "test", "resources", "tool-schema");

  private CatalogData catalog;
  private MarketGuidance guidance;
  private SimulationEngine engine;

  @BeforeEach
  void setUp() {
    catalog = new CsvCatalogLoader().load(DATA);
    guidance = new StorePlaybookLoader().load(DATA.resolve("store_playbook.json"));
    engine = new SimulationEngine(catalog, RunConfig.defaults(), new RandomStreams(42L));
  }

  private ToolExecutionContext ctx() {
    return new ToolExecutionContext(engine, MAPPER);
  }

  private ObjectNode args() {
    return MAPPER.createObjectNode();
  }

  private ObjectNode args(String key, String value) {
    return MAPPER.createObjectNode().put(key, value);
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

  @Test
  void allQueryToolSchemasMatchPythonGolden() throws Exception {
    List<EcommerceTool> tools =
        List.of(
            new CheckBalanceTool(),
            new CheckWarehouseTool(),
            new CheckStoreStatusTool(),
            new ListProductsTool(),
            new MarketSearchTool(guidance),
            new SupplierSearchTool(),
            new TraceReturnSourcesTool());
    for (EcommerceTool tool : tools) {
      assertSchemaMatchesGolden(tool);
    }
  }

  @Test
  void checkBalanceExposesThreeBucketsAndDate() {
    ObjectNode out = new CheckBalanceTool().execute(args(), ctx());

    assertThat(out.get("bank_balance").asDouble()).isEqualTo(100000.0);
    assertThat(out.has("platform_wallet")).isTrue();
    assertThat(out.has("pending_settlement")).isTrue();
    assertThat(out.has("unshipped_sales_value")).isTrue();
    assertThat(out.get("total").asDouble()).isEqualTo(100000.0);
    assertThat(out.get("upcoming_settlements").isObject()).isTrue();
    assertThat(out.get("day").asInt()).isEqualTo(0);
    assertThat(out.get("date").asText()).isEqualTo("2026-01-01");
    assertThat(out.get("note").asText()).contains("pending_settlement");
  }

  @Test
  void checkWarehouseAggregatesItemsBySku() {
    String productId = catalog.products().get(0).productId();
    engine.receivePurchaseOrder("Supplier A", productId, 5, Money.of("10"), false, 0);

    ObjectNode out = new CheckWarehouseTool().execute(args(), ctx());

    assertThat(out.get("total_items").asInt()).isEqualTo(5);
    assertThat(out.get("total_value").asDouble()).isEqualTo(50.0);
    JsonNode item = out.get("items").get(productId);
    assertThat(item.get("quantity").asInt()).isEqualTo(5);
    assertThat(item.get("purchase_price").asDouble()).isEqualTo(10.0);
    assertThat(item.has("product")).isTrue();
    assertThat(item.has("category")).isTrue();
    assertThat(item.has("size")).isTrue();
  }

  @Test
  void listProductsIncludesBrandAndTotal() {
    String category = catalog.products().get(0).category();

    ObjectNode out = new ListProductsTool().execute(args("category", category), ctx());

    assertThat(out.get("total").asInt()).isEqualTo(out.get("results").size());
    JsonNode first = out.get("results").get(0);
    assertThat(first.has("product_id")).isTrue();
    assertThat(first.has("brand")).isTrue();
    assertThat(first.has("reference_price")).isTrue();
    assertThat(first.get("title").asText().length()).isLessThanOrEqualTo(80);
  }

  @Test
  void marketSearchExposesThreeProgressiveLevels() {
    MarketSearchTool tool = new MarketSearchTool(guidance);

    ObjectNode overview = tool.execute(args(), ctx());
    assertThat(overview.get("view").asText()).isEqualTo("store_type_overview");
    assertThat(overview.get("count").asInt()).isEqualTo(catalog.storeTypes().size());
    JsonNode row = overview.get("store_types").get(0);
    assertThat(row.has("profit_potential")).isTrue();
    assertThat(row.has("store_advantage")).isTrue();
    assertThat(row.has("strengths")).isTrue();
    assertThat(row.get("monthly_sales_index").size()).isEqualTo(12);
    assertThat(row.has("season_peak_month")).isTrue();
    assertThat(row.has("season_low_month")).isTrue();

    ObjectNode store = tool.execute(args("store_type", "beauty"), ctx());
    assertThat(store.get("view").asText()).isEqualTo("store_detail");
    assertThat(store.has("operating_tips")).isTrue();
    assertThat(store.get("subcategories").get(0).has("reference_price_range")).isTrue();

    ObjectNode subcategory = tool.execute(args("category", "Skincare & Beauty"), ctx());
    assertThat(subcategory.get("view").asText()).isEqualTo("subcategory_detail");
    assertThat(subcategory.get("detail").has("typical_gross_margin")).isTrue();
    assertThat(subcategory.get("detail").has("shipping_cost_per_unit")).isTrue();
    assertThat(subcategory.get("detail").has("return_rate_note")).isTrue();
  }

  @Test
  void supplierSearchReturnsNoteResultsAndCount() {
    ObjectNode out = new SupplierSearchTool().execute(args(), ctx());

    assertThat(out.has("note")).isTrue();
    assertThat(out.get("count").asInt()).isEqualTo(out.get("results").size());
    JsonNode first = out.get("results").get(0);
    assertThat(first.has("supplier_name")).isTrue();
    assertThat(first.has("supplier_email")).isTrue();
    assertThat(first.get("categories_served").isArray()).isTrue();
  }

  @Test
  void traceReturnSourcesEmitsProductsOrError() {
    ObjectNode empty = new TraceReturnSourcesTool().execute(args(), ctx());
    assertThat(empty.get("products").isArray()).isTrue();
    assertThat(empty.get("products").size()).isEqualTo(0);

    ObjectNode error =
        new TraceReturnSourcesTool().execute(args("product_id", "does-not-exist"), ctx());
    assertThat(error.has("error")).isTrue();
  }

  @Test
  void checkStoreStatusSummarizesAllAndDetailsOne() {
    ObjectNode summary = new CheckStoreStatusTool().execute(args(), ctx());
    assertThat(summary.get("open_stores").asInt()).isEqualTo(0);
    assertThat(summary.get("max_stores").asInt()).isEqualTo(4);
    assertThat(summary.get("stores").size()).isEqualTo(0);

    String storeId = engine.openStore("beauty", "My Beauty").storeId();
    ObjectNode detail = new CheckStoreStatusTool().execute(args("store_id", storeId), ctx());
    assertThat(detail.get("store_id").asText()).isEqualTo(storeId);
    assertThat(detail.has("opened_date")).isTrue();
    assertThat(detail.get("yesterday_summary").has("net")).isTrue();
    assertThat(detail.has("products")).isTrue();

    ObjectNode notFound = new CheckStoreStatusTool().execute(args("store_id", "store_999"), ctx());
    assertThat(notFound.has("error")).isTrue();
  }
}
