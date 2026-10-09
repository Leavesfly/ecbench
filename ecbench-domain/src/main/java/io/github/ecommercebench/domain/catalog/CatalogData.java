package io.github.ecommercebench.domain.catalog;

import java.util.List;
import java.util.Map;

/** 仿真所需的只读业务目录聚合。 */
public record CatalogData(
    List<Product> products,
    List<Supplier> suppliers,
    Map<String, CategoryParams> categoryParams,
    Map<String, StoreTypeConfig> storeTypes,
    List<PromotionConfig> promotions,
    List<MarketEvent> events) {

  public CatalogData {
    products = List.copyOf(products);
    suppliers = List.copyOf(suppliers);
    categoryParams = Map.copyOf(categoryParams);
    storeTypes = Map.copyOf(storeTypes);
    promotions = List.copyOf(promotions);
    events = List.copyOf(events);
  }
}
