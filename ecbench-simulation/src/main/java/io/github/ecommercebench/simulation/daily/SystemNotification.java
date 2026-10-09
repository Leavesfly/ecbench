package io.github.ecommercebench.simulation.daily;

import java.time.LocalDate;

/**
 * 每日推进产生的新闻、履约或余额提醒。
 */
public record SystemNotification(String type, LocalDate date, String content) {
}
