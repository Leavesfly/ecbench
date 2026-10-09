package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

import java.util.List;

/**
 * 开店操作结果。
 */
public record OpenStoreResult(
        boolean success,
        String storeId,
        String storeType,
        String storeName,
        Money setupFeeCharged,
        Money dailyOpsCost,
        boolean reopen,
        List<String> allowedCategories,
        Money bankBalance,
        String error) {

    public OpenStoreResult {
        allowedCategories = allowedCategories == null ? List.of() : List.copyOf(allowedCategories);
    }

    public static OpenStoreResult failure(String error, Money bankBalance) {
        return new OpenStoreResult(
                false, null, null, null, Money.ZERO, Money.ZERO, false, List.of(), bankBalance, error);
    }
}
