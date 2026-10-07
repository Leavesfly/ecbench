package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

/** 仓库查询中的单个 SKU 汇总。 */
public record WarehouseRow(
    String productId,
    int quantity,
    int physicalQuantity,
    String product,
    String category,
    Money purchasePrice,
    String size) {}
