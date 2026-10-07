package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 已售出但尚未由 Agent 发货的订单批次。 */
public record PendingShipment(
    long shipmentId,
    String storeId,
    String productId,
    int quantity,
    Money unitPrice,
    Money revenueGross,
    Money revenueNet,
    Money commission,
    Money purchaseUnitPrice,
    LocalDate saleDate,
    LocalDate deadline,
    BigDecimal baseReturnRate,
    BigDecimal returnRateBeforeDefect,
    BigDecimal returnRateAfterDefect,
    BigDecimal returnRateAfterPrice,
    BigDecimal referencePrice) {}
