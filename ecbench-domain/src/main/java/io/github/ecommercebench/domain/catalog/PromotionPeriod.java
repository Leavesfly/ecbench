package io.github.ecommercebench.domain.catalog;

import java.time.MonthDay;
import java.util.Objects;

/**
 * 不绑定具体年份的促销起止区间。
 */
public record PromotionPeriod(MonthDay start, MonthDay end) {

    public PromotionPeriod {
        Objects.requireNonNull(start, "start 不能为空");
        Objects.requireNonNull(end, "end 不能为空");
    }
}
