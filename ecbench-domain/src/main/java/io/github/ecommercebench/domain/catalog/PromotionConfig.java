package io.github.ecommercebench.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 平台促销活动定义，一个活动可以包含多个日期区间。
 *
 * @param eventName 促销活动名称
 * @param periods 促销日期区间列表，构造时冻结为不可变拷贝
 * @param maxDemandMultiplier 促销高峰期的最大需求乘数
 * @param elasticityBoost 促销期间对需求弹性的额外放大量
 */
public record PromotionConfig(
    String eventName,
    List<PromotionPeriod> periods,
    BigDecimal maxDemandMultiplier,
    BigDecimal elasticityBoost) {

  /** 紧凑构造器：锁定活动名并将日期区间列表冻结为不可变拷贝。 */
  public PromotionConfig {
    Objects.requireNonNull(eventName, "eventName 不能为空");
    periods = List.copyOf(periods);
  }
}
