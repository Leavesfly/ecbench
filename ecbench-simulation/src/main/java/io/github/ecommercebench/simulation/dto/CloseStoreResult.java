package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

import java.util.Map;

/**
 * 关店操作结果：一次 close_store 调用的成功/失败快照。
 *
 * <p>由 {@code SimulationEngine#closeStore} 生成。成功时根据是否清算只回填其中一组明细：
 * {@code liquidated=true} 时 {@code itemsLiquidated} 非空（库存按残值变现入账），
 * 否则 {@code inventoryReturned} 非空（释放占用、实物仍留在仓库）。
 * 失败时两组明细均为空、金额为 0，只有 error 与当前 bankBalance 有意义。
 *
 * @param success           是否关店成功
 * @param storeId           目标店铺 ID（失败时仍回填调用方传入的值，便于定位）
 * @param liquidated        本次是否走清算变现分支，等于调用方传入的 liquidate
 * @param inventoryReturned 退回仓库的 SKU → 件数；仅未清算时有值，已清算时为空 Map
 * @param itemsLiquidated   清算明细 SKU → 件数与残值；仅已清算时有值，未清算时为空 Map
 * @param salvageCredited   本次计入银行的清算残值合计（进价 × LIQUIDATION_SALVAGE_RATE，现为 10%）
 * @param bankBalance       关店后的银行余额快照（含已入账残值）
 * @param error             失败原因，成功时为 null
 */
public record CloseStoreResult(
        boolean success,
        String storeId,
        boolean liquidated,
        Map<String, Integer> inventoryReturned,
        Map<String, LiquidatedItem> itemsLiquidated,
        Money salvageCredited,
        Money bankBalance,
        String error) {

    /**
     * 紧凑构造器：把两个明细 Map 的 null 归一为空 Map 并拷贝为不可变视图，调用方无需判空。
     */
    public CloseStoreResult {
        inventoryReturned = inventoryReturned == null ? Map.of() : Map.copyOf(inventoryReturned);
        itemsLiquidated = itemsLiquidated == null ? Map.of() : Map.copyOf(itemsLiquidated);
    }
}
