package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;
import java.util.Map;

/** 关店操作结果。 */
public record CloseStoreResult(
    boolean success,
    String storeId,
    boolean liquidated,
    Map<String, Integer> inventoryReturned,
    Map<String, LiquidatedItem> itemsLiquidated,
    Money salvageCredited,
    Money bankBalance,
    String error) {

  public CloseStoreResult {
    inventoryReturned = inventoryReturned == null ? Map.of() : Map.copyOf(inventoryReturned);
    itemsLiquidated = itemsLiquidated == null ? Map.of() : Map.copyOf(itemsLiquidated);
  }
}
