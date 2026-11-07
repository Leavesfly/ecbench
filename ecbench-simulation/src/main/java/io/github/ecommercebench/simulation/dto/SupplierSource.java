package io.github.ecommercebench.simulation.dto;

/**
 * 某 SKU 的单一供应商到货量与占比，是 {@link ReturnTraceRow#supplierSources()} 的元素。
 *
 * @param supplier       供应商名称（即采购到货时登记的供应商键）
 * @param unitsDelivered 该供应商为该 SKU 累计到货件数
 * @param share          占该 SKU 总到货量的比例 = unitsDelivered / 总到货量；总到货量为 0 时为 0.0
 */
public record SupplierSource(String supplier, int unitsDelivered, double share) {
}
