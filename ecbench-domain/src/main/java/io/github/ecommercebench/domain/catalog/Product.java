package io.github.ecommercebench.domain.catalog;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 商品目录中的不可变商品定义。
 *
 * @param productId 商品唯一标识
 * @param shopId 所属店铺标识
 * @param category 商品类别，用于关联需求与弹性参数
 * @param storeType 所属店铺类型
 * @param brand 品牌名
 * @param title 商品标题
 * @param size 商品规格
 * @param referencePrice 参考定价，需求模型的基准价
 * @param returnRate 该商品的退货率
 */
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

  /** 紧凑构造器：锁定标识、类别、店型、参考价与退货率等非空字段。 */
  public Product {
    Objects.requireNonNull(productId, "productId 不能为空");
    Objects.requireNonNull(category, "category 不能为空");
    Objects.requireNonNull(storeType, "storeType 不能为空");
    Objects.requireNonNull(referencePrice, "referencePrice 不能为空");
    Objects.requireNonNull(returnRate, "returnRate 不能为空");
  }
}
