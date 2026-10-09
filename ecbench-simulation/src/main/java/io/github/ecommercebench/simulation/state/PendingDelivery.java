package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;

/**
 * 已付款、等待送达仓库的采购批次。
 */
public record PendingDelivery(
        long deliveryId,
        String supplierId,
        String productId,
        int quantity,
        Money unitPrice,
        LocalDate arrivalDate,
        boolean defective) {
}
