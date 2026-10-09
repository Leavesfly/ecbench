package io.github.ecommercebench.domain.catalog;

import io.github.ecommercebench.domain.money.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 店铺类型及其开店、运营、佣金、可售类别和季节性配置。
 */
public record StoreTypeConfig(
        String storeTypeId,
        String storeTypeName,
        int tier,
        Money setupFee,
        Money dailyRent,
        BigDecimal salesCommissionRate,
        List<String> allowedCategories,
        int numCategories,
        List<BigDecimal> seasonality) {

    public StoreTypeConfig {
        Objects.requireNonNull(storeTypeId, "storeTypeId 不能为空");
        allowedCategories = List.copyOf(allowedCategories);
        seasonality = List.copyOf(seasonality);
        if (seasonality.size() != 12) {
            throw new IllegalArgumentException("seasonality 必须包含 12 个月份系数");
        }
    }
}
