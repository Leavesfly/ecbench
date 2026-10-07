package io.github.ecommercebench.simulation.dto;

import java.util.List;

/** SKU 的已实现退货率和公开供应来源。 */
public record ReturnTraceRow(
    String productId,
    String title,
    String category,
    int totalUnitsDelivered,
    int unitsSold,
    int unitsReturned,
    double realizedReturnRate,
    double naturalBaselineReturnRate,
    List<SupplierSource> supplierSources,
    String note) {
  public ReturnTraceRow {
    supplierSources = List.copyOf(supplierSources);
  }
}
