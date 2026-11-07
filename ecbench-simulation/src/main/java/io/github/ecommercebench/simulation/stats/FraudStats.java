package io.github.ecommercebench.simulation.stats;

import io.github.ecommercebench.domain.money.Money;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 欺诈识别统计：记录落入问题供应商的支出，并按欺诈类型与好供应商人格分桶。
 *
 * <p>记账分两处：{@link #recordSpend} / {@link #recordVipFee} 在扣款时逐笔记支出，
 * {@link #recordOrder} 在订单确认后记订单与件数聚合。为避免重复计支出，recordOrder 只累加 spendTotal，
 * 不再写 spendOnBadSupplier 与 perType（这两项由 recordSpend 负责）。指标面板见
 * MetricsJsonWriter 的 fraud_identification。
 */
public final class FraudStats {

    /**
     * 落入 bad 供应商的支出合计；VIP 会员费同样走 recordSpend 记账，因此也计入本值。
     */
    private Money spendOnBadSupplier = Money.ZERO;

    /**
     * 欺诈类型 → 该类支出金额合计，key 为 FraudType 的 wireName。
     */
    private final Map<String, Money> perType = new LinkedHashMap<>();

    /**
     * VIP 会员费支付次数：每次成功支付计 1，与订单笔数无关。
     */
    private int vipFeePaidCount;

    /**
     * 已确认订单总笔数（采购单口径，不含 VIP 会员费）。
     */
    private int ordersTotal;

    /**
     * 其中来自 bad 供应商的订单笔数。
     */
    private int ordersFromBadSupplier;

    /**
     * 已确认订单实付总额 + 独立 VIP 会员费支出。
     */
    private Money spendTotal = Money.ZERO;

    /**
     * VIP 会员费金额合计；同一笔费用另会计入 spendOnBadSupplier 与 perType。
     */
    private Money vipFeePaidAmount = Money.ZERO;

    /**
     * 欺诈类型 → 该类欺诈订单笔数。
     */
    private final Map<String, Integer> perTypeOrders = new LinkedHashMap<>();

    /**
     * 欺诈类型 → 该类欺诈订单的订购件数（按订购量而非实际交付量计）。
     */
    private final Map<String, Integer> perTypeUnits = new LinkedHashMap<>();

    /**
     * 好供应商人格 → 累计采购支出，用于观察 Agent 是否反复选中某类人格。
     */
    private final Map<String, Money> spendByPersonality = new LinkedHashMap<>();

    /**
     * 记录一笔落入问题供应商的支出：同时累加到 bad 供应商总支出与对应欺诈类型分桶。
     *
     * <p>由 OrderProcessor 在欺诈订单扣款后调用；VIP 会员费也走这里（类型 vip_fee），
     * 因此同一笔会员费会同时体现在 {@link #spendOnBadSupplier} 与 {@link #perType} 中。
     *
     * @param fraudType 欺诈类型 wireName
     * @param amount    本笔实付金额
     */
    public void recordSpend(String fraudType, Money amount) {
        spendOnBadSupplier = spendOnBadSupplier.add(amount);
        perType.merge(fraudType, amount, Money::add);
    }

    /**
     * VIP 会员费支付次数 +1，输出为 fraud_identification 的 vip_fee_paid_count。
     */
    public void recordVipFee() {
        vipFeePaidCount++;
    }

    /**
     * 记录一笔已确认订单的聚合口径（端口 Python order_processor 的单点记账）。
     *
     * <p>订单数与总支出每单计一次；bad 供应商额外计 bad 供应商订单数与按欺诈类型的订单/件数
     * （件数取订购量而非实际交付量，支出已由 {@link #recordSpend} 记入，此处不重复）；好供应商按人格累计支出。
     *
     * @param fromBadSupplier 是否来自 bad 供应商
     * @param fraudType       欺诈类型 wireName，为空时不计入按类型分桶
     * @param personality     供应商人格，仅好供应商参与按人格累计
     * @param units           本单订购件数
     * @param spend           本单实付总额
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

    /**
     * 记录一笔独立的 VIP 会员费支出：计入总支出与 VIP 已付金额（不增订单数，与商品订单区分）。
     */
    public void recordVipSpend(Money amount) {
        spendTotal = spendTotal.add(amount);
        vipFeePaidAmount = vipFeePaidAmount.add(amount);
    }

    /**
     * bad 供应商支出合计，输出为 spend_on_bad_supplier。
     */
    public Money spendOnBadSupplier() {
        return spendOnBadSupplier;
    }

    /**
     * 欺诈类型 → 支出金额；返回不可修改视图，未出现过的类型不在键集中。
     */
    public Map<String, Money> perType() {
        return Collections.unmodifiableMap(perType);
    }

    /**
     * VIP 会员费支付次数，输出为 vip_fee_paid_count。
     */
    public int vipFeePaidCount() {
        return vipFeePaidCount;
    }

    /**
     * 已确认订单总笔数，输出为 orders_total。
     */
    public int ordersTotal() {
        return ordersTotal;
    }

    /**
     * bad 供应商订单笔数，输出为 orders_from_bad_supplier。
     */
    public int ordersFromBadSupplier() {
        return ordersFromBadSupplier;
    }

    /**
     * 订单实付总额 + 独立 VIP 会员费，输出为 spend_total。
     */
    public Money spendTotal() {
        return spendTotal;
    }

    /**
     * VIP 会员费金额合计，输出为 vip_fee_paid_amount。
     */
    public Money vipFeePaidAmount() {
        return vipFeePaidAmount;
    }

    /**
     * 欺诈类型 → 订单笔数；返回不可修改视图。
     */
    public Map<String, Integer> perTypeOrders() {
        return Collections.unmodifiableMap(perTypeOrders);
    }

    /**
     * 欺诈类型 → 订购件数；返回不可修改视图。
     */
    public Map<String, Integer> perTypeUnits() {
        return Collections.unmodifiableMap(perTypeUnits);
    }

    /**
     * 好供应商人格 → 累计支出；返回不可修改视图。
     */
    public Map<String, Money> spendByPersonality() {
        return Collections.unmodifiableMap(spendByPersonality);
    }
}
