package io.github.ecommercebench.domain.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.MonthDay;
import java.util.Map;
import java.util.Objects;

/**
 * 会在年度仿真中影响需求或供应链的市场事件。
 */
public record MarketEvent(
        String eventName,
        MonthDay startDate,
        int durationDays,
        Map<String, BigDecimal> demandEffects,
        JsonNode supplyEffects,
        String newsContent) {

    public MarketEvent {
        Objects.requireNonNull(eventName, "eventName 不能为空");
        Objects.requireNonNull(startDate, "startDate 不能为空");
        demandEffects = Map.copyOf(demandEffects);
        Objects.requireNonNull(supplyEffects, "supplyEffects 不能为空");
    }
}
