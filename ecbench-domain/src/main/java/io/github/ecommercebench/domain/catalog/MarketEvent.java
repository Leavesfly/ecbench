package io.github.ecommercebench.domain.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.MonthDay;
import java.util.Map;
import java.util.Objects;

/**
 * 会在年度仿真中影响需求或供应链的市场事件。
 *
 * @param eventName 事件名称，作为新闻与日志的展示标题
 * @param startDate 事件开始日期（不绑定具体年份的月日）
 * @param durationDays 事件持续天数
 * @param demandEffects 按类别或全局索引的需求乘数，构造时冻结为不可变拷贝
 * @param supplyEffects 供应链影响描述（保留原始 JSON 结构）
 * @param newsContent 推送给 Agent 的新闻正文
 */
public record MarketEvent(
    String eventName,
    MonthDay startDate,
    int durationDays,
    Map<String, BigDecimal> demandEffects,
    JsonNode supplyEffects,
    String newsContent) {

  /** 紧凑构造器：锁定非空字段并将需求影响表冻结为不可变拷贝。 */
  public MarketEvent {
    Objects.requireNonNull(eventName, "eventName 不能为空");
    Objects.requireNonNull(startDate, "startDate 不能为空");
    demandEffects = Map.copyOf(demandEffects);
    Objects.requireNonNull(supplyEffects, "supplyEffects 不能为空");
  }
}
