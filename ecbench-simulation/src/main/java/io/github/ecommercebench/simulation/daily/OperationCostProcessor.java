package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.StoreState;

/**
 * 每日扣除每家营业店铺的运营成本，并在第 7 天后对空店经营者收取闲置费。
 */
public final class OperationCostProcessor implements DailyProcessor {

    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        Money total = Money.ZERO;
        for (StoreState store : state.stores().values()) {
            if (store.isOpen()) {
                total = total.add(store.dailyRent());
            }
        }
        if (!total.isZero()) {
            state.accounts().chargeBank(total);
        }
        result.setOpsCostCharged(total);
        if (state.openStoreCount() == 0 && state.dayCount() > 7) {
            state.accounts().chargeBank(EconomicRules.IDLE_DAILY_PENALTY);
            result.setIdlePenaltyCharged(EconomicRules.IDLE_DAILY_PENALTY);
            result.addNotification(
                    new SystemNotification(
                            "penalty", context.day(), "Platform idle-occupancy penalty: ¥1000 charged."));
        }
    }
}
