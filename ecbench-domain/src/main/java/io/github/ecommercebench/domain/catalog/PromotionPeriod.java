package io.github.ecommercebench.domain.catalog;

import java.time.MonthDay;
import java.util.Objects;

/**
 * 不绑定具体年份的促销起止区间。
 *
 * @param start 区间开始日期（月日）
 * @param end 区间结束日期（月日）
 */
public record PromotionPeriod(MonthDay start, MonthDay end) {

  /** 紧凑构造器：锁定起止日期非空。 */
  public PromotionPeriod {
    Objects.requireNonNull(start, "start 不能为空");
    Objects.requireNonNull(end, "end 不能为空");
  }
}
