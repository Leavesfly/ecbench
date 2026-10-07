package io.github.ecommercebench.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 平台促销活动定义，一个活动可以包含多个日期区间。 */
public record PromotionConfig(
    String eventName,
    List<PromotionPeriod> periods,
    BigDecimal maxDemandMultiplier,
    BigDecimal elasticityBoost) {

  public PromotionConfig {
    Objects.requireNonNull(eventName, "eventName 不能为空");
    periods = List.copyOf(periods);
  }
}
