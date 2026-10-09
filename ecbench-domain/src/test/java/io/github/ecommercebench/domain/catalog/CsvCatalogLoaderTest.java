package io.github.ecommercebench.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.domain.error.DataValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvCatalogLoaderTest {

  @Test
  void loadsCompleteRepositoryCatalog() {
    Path dataDirectory = Path.of(System.getProperty("user.dir"), "..", "data").normalize();

    CatalogData catalog = new CsvCatalogLoader().load(dataDirectory);

    assertThat(catalog.products()).hasSize(6886);
    assertThat(catalog.suppliers()).hasSize(576);
    assertThat(catalog.storeTypes()).containsKeys("appliance_digital", "beauty");
    assertThat(catalog.categoryParams()).containsKey("Major Appliances");
    assertThat(catalog.promotions()).isNotEmpty();
    assertThat(catalog.events()).isNotEmpty();
    assertThat(catalog.suppliers().stream().filter(Supplier::isFraudulent).count()).isEqualTo(152);
  }

  @Test
  void catalogCollectionsAreImmutable() {
    Product product =
        new Product(
            "p1",
            "001",
            "category",
            "beauty",
            "brand",
            "title",
            "Small",
            new java.math.BigDecimal("10.00"),
            new java.math.BigDecimal("0.10"));
    CatalogData catalog =
        new CatalogData(
            java.util.List.of(product),
            java.util.List.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.List.of(),
            java.util.List.of());

    assertThatThrownBy(() -> catalog.products().add(product))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsProductsFileWithMissingRequiredColumn(@TempDir Path directory) throws IOException {
    Files.writeString(
        directory.resolve("products.csv"),
        "product_id,shop_id,category,store_type,brand,title,size,return_rate\n"
            + "p1,001,category,beauty,brand,Example,Small,0.10\n");

    assertThatThrownBy(() -> new CsvCatalogLoader().load(directory))
        .isInstanceOf(DataValidationException.class)
        .hasMessageContaining("products.csv")
        .hasMessageContaining("reference_price");
  }
}
