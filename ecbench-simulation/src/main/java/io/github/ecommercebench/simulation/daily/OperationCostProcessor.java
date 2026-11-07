package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.StoreState;

/**
 * 每日扣除每家营业店铺的运营成本，并在第 7 天后对空店经营者收取闲置费。
 *
 * <p>作为日切流水线的第一步，先扣成本、再由后续步骤产生当日收入；两笔支出都直接调用 chargeBank
 * （允许把银行余额扣成负数），负余额连续天数与破产判定交给当日最后的 {@link BankruptcyProcessor}。
 */
public final class OperationCostProcessor implements DailyProcessor {

    /**
     * 汇总所有营业店铺的每日运营成本并一次性扣款、写入当日摘要；若一家店都没开且已过第 7 天，
     * 再追加 1000 元闲置罚金与系统通知。
     */
    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        Money total = Money.ZERO;
        // 只对营业中店铺收费：已关闭店铺仍留在 stores 表中但不计租；每家店按各自 tier 的成本全额各付一份。
        // 店铺的 dailyRent 即开店时按 tier 取定的 EconomicRules.operationsCost（130/100/60，未知 tier 回退 80）。
        for (StoreState store : state.stores().values()) {
            if (store.isOpen()) {
                total = total.add(store.dailyRent());
            }
        }
        // 合并成一次扣款；余额不足时允许扣成负数（由当日最后的破产判定接手），故此处不做余额校验。
        if (!total.isZero()) {
            state.accounts().chargeBank(total);
        }
        // 当日摘要为每天新建对象，此处是置位而非累加。
        result.setOpsCostCharged(total);
        // 第 7 天后（dayCount 在日切时先自增，故 >7 即第 8 天起）若一家店都没开（从未开店或全部关店），
        // 视为空占账户，每天额外罚 1000 并推送通知。
        if (state.openStoreCount() == 0 && state.dayCount() > 7) {
            state.accounts().chargeBank(EconomicRules.IDLE_DAILY_PENALTY);
            result.setIdlePenaltyCharged(EconomicRules.IDLE_DAILY_PENALTY);
            result.addNotification(
                    new SystemNotification(
                            "penalty", context.day(), "平台空置占用罚金：已扣 ¥1000。"));
        }
    }
}
