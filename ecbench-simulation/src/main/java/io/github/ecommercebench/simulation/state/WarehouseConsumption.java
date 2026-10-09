package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

/**
 * FIFO 出库结果，同时保留缺陷数量和采购成本以支持退货归因。
 */
public record WarehouseConsumption(int quantity, int defectiveQuantity, Money purchaseCost) {
}
