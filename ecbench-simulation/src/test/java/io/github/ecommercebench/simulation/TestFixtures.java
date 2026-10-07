package io.github.ecommercebench.simulation;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.PromotionConfig;
import io.github.ecommercebench.domain.catalog.PromotionPeriod;
import io.github.ecommercebench.domain.catalog.StoreTypeConfig;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import java.math.BigDecimal;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;

final class TestFixtures {

  private TestFixtures() {}

  static CatalogData catalog() {
    Product product =
        new Product(
            "sku-1",
            "001",
            "Personal Care",
            "beauty",
            "Brand",
            "Product",
            "Small",
            new BigDecimal("100"),
            BigDecimal.ZERO);
    CategoryParams category =
        new CategoryParams(
            "Personal Care",
            "beauty",
            "Small",
            new BigDecimal("50"),
            new BigDecimal("150"),
            3000,
            3000,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "test",
            "linear",
            BigDecimal.ONE,
            new BigDecimal("0.60"),
            new BigDecimal("0.40"),
            new BigDecimal("0.70"));
    List<BigDecimal> seasonality = java.util.Collections.nCopies(12, BigDecimal.ONE);
    Map<String, StoreTypeConfig> stores =
        Map.of(
            "beauty", store("beauty", 3, seasonality),
            "fashion", store("fashion", 2, seasonality),
            "home_living", store("home_living", 1, seasonality),
            "pet", store("pet", 3, seasonality));
    Supplier supplier =
        new Supplier(
            "SUP-1",
            "Sample Supply",
            "sample@example.com",
            "good",
            "Friendly",
            0.5,
            null,
            List.of("Personal Care"),
            10);
    PromotionConfig promotion =
        new PromotionConfig(
            "New Year Kickoff Sale",
            List.of(new PromotionPeriod(MonthDay.of(1, 1), MonthDay.of(1, 7))),
            new BigDecimal("2.0"),
            new BigDecimal("1.5"));
    return new CatalogData(
        List.of(product),
        List.of(supplier),
        Map.of(category.category(), category),
        stores,
        List.of(promotion),
        List.of());
  }

  static RunConfig config() {
    return RunConfig.defaults();
  }

  private static StoreTypeConfig store(String id, int tier, List<BigDecimal> seasonality) {
    return new StoreTypeConfig(
        id,
        id,
        tier,
        Money.of("500"),
        Money.of(tier == 1 ? "130" : tier == 2 ? "100" : "45"),
        new BigDecimal("0.02"),
        List.of("Personal Care"),
        1,
        seasonality);
  }
}
