package io.github.ecommercebench.domain.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.error.DataValidationException;
import io.github.ecommercebench.domain.money.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.MonthDay;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * 读取仓库 data 目录中的六类 CSV，并在启动阶段完成表头和字段类型校验。
 *
 * <p>任何坏行都携带文件名和 CSV 记录号，避免在长周期运行中才暴露静态数据问题。
 */
public final class CsvCatalogLoader {

  private static final CSVFormat CSV_FORMAT =
      CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
  private final ObjectMapper objectMapper;

  public CsvCatalogLoader() {
    this(new ObjectMapper());
  }

  public CsvCatalogLoader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public CatalogData load(Path dataDirectory) {
    List<Product> products = loadProducts(dataDirectory.resolve("products.csv"));
    List<Supplier> suppliers = loadSuppliers(dataDirectory.resolve("suppliers.csv"));
    Map<String, CategoryParams> categoryParams =
        loadCategoryParams(dataDirectory.resolve("category_params.csv"));
    Map<String, StoreTypeConfig> storeTypes =
        loadStoreTypes(dataDirectory.resolve("store_types.csv"));
    List<PromotionConfig> promotions = loadPromotions(dataDirectory.resolve("promotions.csv"));
    List<MarketEvent> events = loadEvents(dataDirectory.resolve("events.csv"));
    return new CatalogData(products, suppliers, categoryParams, storeTypes, promotions, events);
  }

  private List<Product> loadProducts(Path file) {
    String[] required = {
      "product_id",
      "shop_id",
      "category",
      "store_type",
      "brand",
      "title",
      "size",
      "reference_price",
      "return_rate"
    };
    return parse(
        file,
        required,
        row ->
            new Product(
                row.get("product_id"),
                row.get("shop_id"),
                row.get("category"),
                row.get("store_type"),
                row.get("brand"),
                row.get("title"),
                row.get("size"),
                decimal(row, "reference_price"),
                decimal(row, "return_rate")));
  }

  private List<Supplier> loadSuppliers(Path file) {
    String[] required = {
      "supplier_id",
      "supplier_name",
      "supplier_email",
      "supplier_type",
      "personality",
      "urgency",
      "fraud_type",
      "categories_served",
      "bankruptcy_threshold"
    };
    return parse(
        file,
        required,
        row ->
            new Supplier(
                row.get("supplier_id"),
                row.get("supplier_name"),
                row.get("supplier_email"),
                row.get("supplier_type"),
                row.get("personality"),
                decimal(row, "urgency").doubleValue(),
                row.get("fraud_type"),
                splitPipe(row.get("categories_served")),
                integer(row, "bankruptcy_threshold")));
  }

  private Map<String, CategoryParams> loadCategoryParams(Path file) {
    String[] required = {
      "category",
      "store_type",
      "default_size",
      "ref_price_min",
      "ref_price_max",
      "monthly_sales_min",
      "monthly_sales_max",
      "return_rate_min",
      "return_rate_max",
      "return_rate_description",
      "elasticity_type",
      "elasticity_param",
      "wholesale_ratio",
      "cost_floor_ratio",
      "scam_cap_ratio"
    };
    List<CategoryParams> values =
        parse(
            file,
            required,
            row ->
                new CategoryParams(
                    row.get("category"),
                    row.get("store_type"),
                    row.get("default_size"),
                    decimal(row, "ref_price_min"),
                    decimal(row, "ref_price_max"),
                    integer(row, "monthly_sales_min"),
                    integer(row, "monthly_sales_max"),
                    decimal(row, "return_rate_min"),
                    decimal(row, "return_rate_max"),
                    row.get("return_rate_description"),
                    row.get("elasticity_type"),
                    decimal(row, "elasticity_param"),
                    decimal(row, "wholesale_ratio"),
                    decimal(row, "cost_floor_ratio"),
                    decimal(row, "scam_cap_ratio")));
    Map<String, CategoryParams> result = new LinkedHashMap<>();
    values.forEach(value -> result.put(value.category(), value));
    return result;
  }

  private Map<String, StoreTypeConfig> loadStoreTypes(Path file) {
    String[] required = {
      "store_type_id",
      "store_type_name",
      "tier",
      "setup_fee",
      "daily_rent",
      "sales_commission_rate",
      "allowed_categories",
      "num_categories"
    };
    List<StoreTypeConfig> values =
        parse(
            file,
            required,
            row ->
                new StoreTypeConfig(
                    row.get("store_type_id"),
                    row.get("store_type_name"),
                    integer(row, "tier"),
                    Money.of(row.get("setup_fee")),
                    Money.of(row.get("daily_rent")),
                    decimal(row, "sales_commission_rate"),
                    splitPipe(row.get("allowed_categories")),
                    integer(row, "num_categories"),
                    Seasonality.forStoreType(row.get("store_type_id"))));
    Map<String, StoreTypeConfig> result = new LinkedHashMap<>();
    values.forEach(value -> result.put(value.storeTypeId(), value));
    return result;
  }

  private List<PromotionConfig> loadPromotions(Path file) {
    String[] required = {"event_name", "periods", "max_demand_multiplier", "elasticity_boost"};
    return parse(
        file,
        required,
        row ->
            new PromotionConfig(
                row.get("event_name"),
                Arrays.stream(row.get("periods").split("\\|")).map(this::promotionPeriod).toList(),
                decimal(row, "max_demand_multiplier"),
                decimal(row, "elasticity_boost")));
  }

  private List<MarketEvent> loadEvents(Path file) {
    String[] required = {
      "event_name",
      "start_date",
      "duration_days",
      "demand_effects",
      "supply_effects",
      "news_content"
    };
    return parse(
        file,
        required,
        row ->
            new MarketEvent(
                row.get("event_name"),
                parseMonthDay(row.get("start_date")),
                integer(row, "duration_days"),
                demandEffects(row.get("demand_effects")),
                json(row.get("supply_effects")),
                row.get("news_content")));
  }

  private PromotionPeriod promotionPeriod(String value) {
    String[] parts = value.split(":", -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("无效促销日期区间: " + value);
    }
    return new PromotionPeriod(parseMonthDay(parts[0]), parseMonthDay(parts[1]));
  }

  private MonthDay parseMonthDay(String value) {
    return MonthDay.parse("--" + value);
  }

  private Map<String, BigDecimal> demandEffects(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("无效 demand_effects JSON", exception);
    }
  }

  private JsonNode json(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("无效 JSON", exception);
    }
  }

  private static List<String> splitPipe(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split("\\|"))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .toList();
  }

  private static BigDecimal decimal(CSVRecord row, String column) {
    return new BigDecimal(row.get(column));
  }

  private static int integer(CSVRecord row, String column) {
    return Integer.parseInt(row.get(column));
  }

  private <T> List<T> parse(Path file, String[] requiredColumns, RowMapper<T> mapper) {
    if (!Files.isRegularFile(file)) {
      throw new DataValidationException("数据文件不存在: " + file);
    }
    try (CSVParser parser = CSVParser.parse(file, StandardCharsets.UTF_8, CSV_FORMAT)) {
      Set<String> headers = parser.getHeaderMap().keySet();
      for (String required : requiredColumns) {
        if (!headers.contains(required)) {
          throw new DataValidationException(file.getFileName() + " 缺少必需列: " + required);
        }
      }
      java.util.ArrayList<T> values = new java.util.ArrayList<>();
      for (CSVRecord record : parser) {
        try {
          values.add(mapper.map(record));
        } catch (RuntimeException exception) {
          throw new DataValidationException(
              file.getFileName()
                  + " 第 "
                  + record.getRecordNumber()
                  + " 条记录无效: "
                  + exception.getMessage(),
              exception);
        }
      }
      return List.copyOf(values);
    } catch (IOException exception) {
      throw new DataValidationException("读取数据文件失败: " + file, exception);
    }
  }

  @FunctionalInterface
  private interface RowMapper<T> {
    T map(CSVRecord record);
  }
}
