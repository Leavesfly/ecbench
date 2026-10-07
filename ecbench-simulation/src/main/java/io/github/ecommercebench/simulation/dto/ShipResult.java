package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.state.ShipSpeed;
import java.util.List;

/** 批量发货结果。 */
public record ShipResult(
    boolean success,
    ShipSpeed speed,
    int shippedCount,
    Money totalShippingCost,
    Money totalRevenueIntoEscrow,
    Money bankBalance,
    Money pendingSettlement,
    List<ShippedOrder> shipments,
    String error) {

  public ShipResult {
    shipments = shipments == null ? List.of() : List.copyOf(shipments);
  }

  public static ShipResult failure(String error, Money bank, Money escrow) {
    return new ShipResult(false, null, 0, Money.ZERO, Money.ZERO, bank, escrow, List.of(), error);
  }
}
