package io.github.ecommercebench.domain.catalog;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 商品类别的需求、退货率、弹性与采购成本参数。
 */
public record CategoryParams(
        String category,
        String storeType,
        String defaultSize,
        BigDecimal referencePriceMin,
        BigDecimal referencePriceMax,
        int monthlySalesMin,
        int monthlySalesMax,
        BigDecimal returnRateMin,
        BigDecimal returnRateMax,
        String returnRateDescription,
        String elasticityType,
        BigDecimal elasticityParam,
        BigDecimal wholesaleRatio,
        BigDecimal costFloorRatio,
        BigDecimal scamCapRatio) {

    public CategoryParams {
        Objects.requireNonNull(category, "category 不能为空");
        Objects.requireNonNull(storeType, "storeType 不能为空");
        Objects.requireNonNull(elasticityType, "elasticityType 不能为空");
    }
}
