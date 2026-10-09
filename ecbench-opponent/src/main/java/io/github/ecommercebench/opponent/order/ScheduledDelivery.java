package io.github.ecommercebench.opponent.order;

import io.github.ecommercebench.domain.money.Money;

/**
 * 订单层提交给仿真层的待到货信息。
 */
public record ScheduledDelivery(
        String supplierName,
        String skuId,
        int quantity,
        Money unitPrice,
        boolean defective,
        int deliveryDelayDays,
        String shippingAddress) {
}
