package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.StoreState;

/**
 * 用累计销量形成基础声誉，再扣除近期退货和逾期取消惩罚。
 */
public final class ReputationProcessor implements DailyProcessor {
    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        for (StoreState store : state.stores().values()) {
            if (!store.isOpen()) {
                continue;
            }
            double base = 0.3 + 0.7 / (1.0 + Math.exp(-(store.cumulativeSales() - 500.0) / 200.0));
            double denominator = Math.max(1.0, store.recentSold());
            double penalty =
                    0.6 * store.recentReturns() / denominator + store.recentCancellations() / denominator;
            store.updateReputation(base - Math.min(0.5, penalty));
            store.decayRecentServiceCounters();
        }
    }
}
