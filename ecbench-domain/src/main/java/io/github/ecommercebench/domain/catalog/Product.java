package io.github.ecommercebench.domain.catalog;

import java.math.BigDecimal;
import java.util.Objects;

/** 商品目录中的不可变商品定义。 */
public record Product(
    String productId,
    String shopId,
    String category,
    String storeType,
    String brand,
    String title,
    String size,
    BigDecimal referencePrice,
    BigDecimal returnRate) {

  public Product {
    Objects.requireNonNull(productId, "productId 不能为空");
    Objects.requireNonNull(category, "category 不能为空");
    Objects.requireNonNull(storeType, "storeType 不能为空");
    Objects.requireNonNull(referencePrice, "referencePrice 不能为空");
    Objects.requireNonNull(returnRate, "returnRate 不能为空");
  }
}
