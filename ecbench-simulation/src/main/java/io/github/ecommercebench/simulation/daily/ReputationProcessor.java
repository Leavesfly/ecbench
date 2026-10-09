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
            // 基础声誉用 S 型曲线随累计销量从 0.3 渐近至 1.0（拐点约在 500 件）。
            double base = 0.3 + 0.7 / (1.0 + Math.exp(-(store.cumulativeSales() - 500.0) / 200.0));
            double denominator = Math.max(1.0, store.recentSold());
            // 近期退货(权重0.6)与逾期取消按比例扣分，总惩罚上限 0.5，避免声誉瞬时跌到谷底。
            double penalty =
                    0.6 * store.recentReturns() / denominator + store.recentCancellations() / denominator;
            store.updateReputation(base - Math.min(0.5, penalty));
            // 服务计数器按日衰减，使声誉只反映近期表现而非历史全量。
            store.decayRecentServiceCounters();
        }
    }
}
