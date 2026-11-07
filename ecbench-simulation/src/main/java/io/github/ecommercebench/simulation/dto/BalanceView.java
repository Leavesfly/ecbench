package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;
import java.util.Map;

/**
 * 三账户余额与近期结算计划快照。
 *
 * <p>由 {@code SimulationEngine#checkBalance} 生成，{@code check_balance} 工具逐字段透出（托管计划只取前 5 个结算日），
 * 也供运行观察者按日快照总资产算最大回撤。
 *
 * @param bankBalance         银行余额（运维、采购、运费等支出从这里扣，允许为负）
 * @param platformWallet      平台钱包余额（仅可从银行提现进入，提现后才能对外取用）
 * @param pendingSettlement   待结算托管合计：已发货但尚未到期的净收入
 * @param unshippedSalesValue 未发货订单的净收入合计（尚未进托管，不计入 total）
 * @param total               总资产 = bankBalance + platformWallet + pendingSettlement
 * @param upcomingSettlements 托管入账时间表：结算日 → 当日到期金额（已按结算日升序聚合）
 * @param day                 距 episode 起始日的天数序号（START_DATE 当天为 0）
 * @param date                当前模拟日期
 */
public record BalanceView(
        Money bankBalance,
        Money platformWallet,
        Money pendingSettlement,
        Money unshippedSalesValue,
        Money total,
        Map<LocalDate, Money> upcomingSettlements,
        int day,
        LocalDate date) {
    /**
     * 紧凑构造器：把结算时间表拷贝为不可变 Map，避免调用方后续改动影响快照。
     */
    public BalanceView {
        upcomingSettlements = Map.copyOf(upcomingSettlements);
    }
}
