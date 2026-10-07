package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;
import java.time.LocalDate;
import java.util.Map;

/** 三账户余额与近期结算计划。 */
public record BalanceView(
    Money bankBalance,
    Money platformWallet,
    Money pendingSettlement,
    Money unshippedSalesValue,
    Money total,
    Map<LocalDate, Money> upcomingSettlements,
    int day,
    LocalDate date) {
  public BalanceView {
    upcomingSettlements = Map.copyOf(upcomingSettlements);
  }
}
