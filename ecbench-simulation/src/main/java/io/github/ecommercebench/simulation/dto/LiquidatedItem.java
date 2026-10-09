package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

/**
 * 关店清算时单个 SKU 的处理明细。
 */
public record LiquidatedItem(int quantity, Money salvage) {
}
