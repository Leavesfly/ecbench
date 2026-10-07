package io.github.ecommercebench.simulation.dto;

import java.math.BigDecimal;

/** 对 Agent 可见的商品信息，不暴露隐藏供应商成本。 */
public record ProductView(
    String productId,
    String title,
    String category,
    String brand,
    String storeType,
    String size,
    BigDecimal referencePrice,
    BigDecimal returnRate) {}
