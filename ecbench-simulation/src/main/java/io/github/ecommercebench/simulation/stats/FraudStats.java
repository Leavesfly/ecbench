package io.github.ecommercebench.simulation.stats;

import io.github.ecommercebench.domain.money.Money;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 记录落入欺诈供应商的支出，并按欺诈类型拆分。 */
public final class FraudStats {

  private Money spendOnBadSupplier = Money.ZERO;
  private final Map<String, Money> perType = new LinkedHashMap<>();
  private int vipFeePaidCount;
  private int ordersTotal;
  private int ordersFromBadSupplier;
  private Money spendTotal = Money.ZERO;
  private Money vipFeePaidAmount = Money.ZERO;
  private final Map<String, Integer> perTypeOrders = new LinkedHashMap<>();
  private final Map<String, Integer> perTypeUnits = new LinkedHashMap<>();
  private final Map<String, Money> spendByPersonality = new LinkedHashMap<>();

  public void recordSpend(String fraudType, Money amount) {
    spendOnBadSupplier = spendOnBadSupplier.add(amount);
    perType.merge(fraudType, amount, Money::add);
  }

  public void recordVipFee() {
    vipFeePaidCount++;
  }

  /**
   * 记录一笔已确认订单的聚合口径（端口 Python order_processor 的单点记账）。
   *
   * <p>订单数与总支出每单计一次；坑供应商额外计坑供应商订单数与按欺诈类型的订单/件数（支出已由 {@link #recordSpend} 记入， 此处不重复）；好供应商按人格累计支出。
   */
  public void recordOrder(
      boolean fromBadSupplier, String fraudType, String personality, int units, Money spend) {
    ordersTotal++;
    spendTotal = spendTotal.add(spend);
    if (fromBadSupplier) {
      ordersFromBadSupplier++;
      if (fraudType != null && !fraudType.isBlank()) {
        perTypeOrders.merge(fraudType, 1, Integer::sum);
        perTypeUnits.merge(fraudType, units, Integer::sum);
      }
    } else if (personality != null && !personality.isBlank()) {
      spendByPersonality.merge(personality, spend, Money::add);
    }
  }

  /** 记录一笔独立的 VIP 会员费支出：计入总支出与 VIP 已付金额（不增订单数，与商品订单区分）。 */
  public void recordVipSpend(Money amount) {
    spendTotal = spendTotal.add(amount);
    vipFeePaidAmount = vipFeePaidAmount.add(amount);
  }

  public Money spendOnBadSupplier() {
    return spendOnBadSupplier;
  }

  public Map<String, Money> perType() {
    return Collections.unmodifiableMap(perType);
  }

  public int vipFeePaidCount() {
    return vipFeePaidCount;
  }

  public int ordersTotal() {
    return ordersTotal;
  }

  public int ordersFromBadSupplier() {
    return ordersFromBadSupplier;
  }

  public Money spendTotal() {
    return spendTotal;
  }

  public Money vipFeePaidAmount() {
    return vipFeePaidAmount;
  }

  public Map<String, Integer> perTypeOrders() {
    return Collections.unmodifiableMap(perTypeOrders);
  }

  public Map<String, Integer> perTypeUnits() {
    return Collections.unmodifiableMap(perTypeUnits);
  }

  public Map<String, Money> spendByPersonality() {
    return Collections.unmodifiableMap(spendByPersonality);
  }
}
