package io.github.ecommercebench.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.tool.EcommerceTool;
import io.github.ecommercebench.agent.tool.ToolExecutionContext;
import io.github.ecommercebench.agent.tool.ToolSchemas;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.SimulationEngine;
import io.github.ecommercebench.simulation.daily.DailyResult;
import io.github.ecommercebench.simulation.daily.SystemNotification;
import io.github.ecommercebench.simulation.state.StoreState;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * wait_for_next_day：推进到下一个营业日并触发每日结算，输出对齐 Python `wait_for_next_day` 的 Agent 可见负载。
 *
 * <p>必须传入当前日期 {@code current_day} 且等于引擎日期，否则抛业务异常由管理器转成稳定错误。日级模型下本工具是唯一推进日期的入口，
 * 因此当日新闻（system_notifications）与低余额提醒（balance_reminder）在此一并构造。
 */
public final class WaitForNextDayTool implements EcommerceTool {

  private static final String NAME = "wait_for_next_day";

  private final ToolDefinition definition = ToolSchemas.load(NAME);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ToolDefinition definition() {
    return definition;
  }

  @Override
  public ObjectNode execute(JsonNode args, ToolExecutionContext context) {
    String currentDay = ToolArgs.require(args, "current_day");
    LocalDate date;
    try {
      date = LocalDate.parse(currentDay.trim());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("'current_day'");
    }
    DailyResult result = context.engine().advanceToNextDay(date);
    ObjectNode out = context.mapper().createObjectNode();
    out.put("day", result.day());
    out.put("date", result.date().toString());
    out.put("current_time", result.date() + " 08:00");
    addNotifications(out, result);
    addBalanceReminder(out, context.engine());
    return out;
  }

  private void addNotifications(ObjectNode out, DailyResult result) {
    List<SystemNotification> notifications = result.notifications();
    if (notifications.isEmpty()) {
      return;
    }
    ArrayNode notices = out.putArray("system_notifications");
    ObjectNode group = notices.addObject();
    group.put("date", result.date().toString());
    group.put("day", result.day());
    ArrayNode news = group.putArray("news");
    for (SystemNotification notification : notifications) {
      String content = notification.content();
      if (content == null || content.isEmpty()) {
        continue;
      }
      ObjectNode item = news.addObject();
      item.put("type", notification.type());
      item.put("content", ToolArgs.truncate(content, 200));
    }
  }

  private void addBalanceReminder(ObjectNode out, SimulationEngine engine) {
    double bank = engine.state().accounts().bank().amount().doubleValue();
    double dailyFee = currentDailyOpsCost(engine);
    if (bank < dailyFee) {
      out.put(
          "balance_reminder",
          String.format(
              Locale.ROOT,
              "Your current balance (¥%.2f) is below the daily operating cost (¥%.2f). If your "
                  + "bank account stays negative for 10 consecutive days, you will go bankrupt.",
              bank,
              dailyFee));
    }
  }

  /** 下一次日切将收取的运营总成本；无在营店铺时回退到最低档单店运营费。 */
  private double currentDailyOpsCost(SimulationEngine engine) {
    double total = 0.0;
    for (StoreState store : engine.state().stores().values()) {
      if (store.isOpen()) {
        total += store.dailyRent().amount().doubleValue();
      }
    }
    return total > 0 ? total : EconomicRules.operationsCost(3).amount().doubleValue();
  }
}
