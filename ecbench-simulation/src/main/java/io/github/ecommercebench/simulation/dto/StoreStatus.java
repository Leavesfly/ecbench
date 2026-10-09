package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

import java.util.Map;

/**
 * 单店运营和财务状态。
 */
public record StoreStatus(
        StoreSummary summary,
        Map<String, Integer> inventory,
        Map<String, Money> prices,
        Money totalRevenue,
        Money shippingCost,
        Money refunds) {
    public StoreStatus {
        inventory = Map.copyOf(inventory);
        prices = Map.copyOf(prices);
    }
}
