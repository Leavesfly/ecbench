package io.github.ecommercebench.opponent.model;

import io.github.ecommercebench.domain.money.Money;

/** 采购订单执行结果。 */
public record PurchaseOrderOutcome(
    boolean confirmed,
    String message,
    Money chargedAmount,
    int orderedQuantity,
    int deliveredQuantity,
    boolean defective,
    FraudType fraudType,
    int deliveryDelayDays) {

  public static PurchaseOrderOutcome failure(String message, FraudType type) {
    return new PurchaseOrderOutcome(false, message, Money.ZERO, 0, 0, false, type, 0);
  }
}
