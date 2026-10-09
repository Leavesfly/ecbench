package io.github.ecommercebench.simulation.event;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.MarketEvent;
import io.github.ecommercebench.domain.catalog.PromotionConfig;
import io.github.ecommercebench.domain.catalog.PromotionPeriod;
import io.github.ecommercebench.simulation.daily.DailyResult;
import io.github.ecommercebench.simulation.daily.SystemNotification;

import java.time.LocalDate;

/**
 * 根据 CSV 日历产生市场事件和提前七天的促销通知。
 */
public final class EventScheduler {

    /** 追加以当日命中的市场事件新闻，以及处于活跃或 7 天内即将开始的促销公告。 */
    public void appendNotifications(CatalogData catalog, LocalDate day, DailyResult result) {
        for (MarketEvent event : catalog.events()) {
            LocalDate start = event.startDate().atYear(day.getYear());
            LocalDate end = start.plusDays(event.durationDays() - 1L);
            if (!day.isBefore(start) && !day.isAfter(end)) {
                result.addNotification(new SystemNotification("event", day, event.newsContent()));
            }
        }
        for (PromotionConfig promotion : catalog.promotions()) {
            if (isActiveOrUpcoming(promotion, day)) {
                result.addNotification(
                        new SystemNotification(
                                "promotion",
                                day,
                                "[PROMOTION ANNOUNCEMENT] "
                                        + promotion.eventName()
                                        + "\nA major promotional event is coming up! "
                                        + "Use join_promotion to participate with a discount rate."));
            }
        }
    }

    /** 判断促销是否正在进行或者开始日落在未来 7 天窗口内（end 早于 start 视为跨年）。 */
    private boolean isActiveOrUpcoming(PromotionConfig promotion, LocalDate day) {
        LocalDate horizon = day.plusDays(7);
        for (PromotionPeriod period : promotion.periods()) {
            LocalDate start = period.start().atYear(day.getYear());
            LocalDate end = period.end().atYear(day.getYear());
            if (end.isBefore(start)) {
                end = period.end().atYear(day.getYear() + 1);
            }
            if ((!day.isBefore(start) && !day.isAfter(end))
                    || (start.isAfter(day) && !start.isAfter(horizon))) {
                return true;
            }
        }
        return false;
    }
}
