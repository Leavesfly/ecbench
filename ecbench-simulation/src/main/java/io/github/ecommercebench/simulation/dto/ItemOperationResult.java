package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

/**
 * 批量商品操作中的单项结果。
 */
public record ItemOperationResult(
        String productId, boolean success, int quantity, Money oldPrice, Money newPrice, String error) {

    public static ItemOperationResult success(
            String productId, int quantity, Money oldPrice, Money newPrice) {
        return new ItemOperationResult(productId, true, quantity, oldPrice, newPrice, null);
    }

    public static ItemOperationResult failure(String productId, String error) {
        return new ItemOperationResult(productId, false, 0, null, null, error);
    }
}
