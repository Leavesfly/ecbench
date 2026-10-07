package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;
import java.time.LocalDate;

/** 单笔发货结果。 */
public record ShippedOrder(
    long shipmentId,
    String productId,
    int quantity,
    Money shippingCost,
    Money revenueIntoEscrow,
    LocalDate settlesOn) {}
