package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

/**
 * 仓库盘点中的单个 SKU 汇总行。
 *
 * <p>由 {@code SimulationEngine#checkWarehouse} 遍历仓库批次生成，同一 SKU 只出一行；
 * {@code check_warehouse} 工具取 {@code quantity} 与 {@code purchasePrice} 汇总总件数与总货值。
 *
 * @param productId        SKU 编号
 * @param quantity         尚未分配到店铺的可用库存件数（对应 WarehouseInventory.quantityOf）
 * @param physicalQuantity 仓库物理存量，含已上架但未售出的商品（对应 physicalQuantityOf）
 * @param product          商品标题；目录查不到该 SKU 时为空串
 * @param category         商品类目；目录查不到该 SKU 时为空串
 * @param purchasePrice    入库单价；同 SKU 多批次时保留最近一批的单价，不是 FIFO 加权均价
 * @param size             商品规格；目录查不到该 SKU 时为空串
 */
public record WarehouseRow(
        String productId,
        int quantity,
        int physicalQuantity,
        String product,
        String category,
        Money purchasePrice,
        String size) {
}
