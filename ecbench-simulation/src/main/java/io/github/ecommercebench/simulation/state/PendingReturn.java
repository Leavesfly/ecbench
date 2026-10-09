package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已确定会退货、等待返回仓库的商品批次。
 */
public record PendingReturn(
        String storeId,
        String productId,
        int quantity,
        Money refundPerUnit,
        LocalDate arrivalDate,
        long escrowBatchId,
        Money shippingCostPerUnit,
        Money purchaseUnitPrice,
        BigDecimal defectiveFraction) {
}
