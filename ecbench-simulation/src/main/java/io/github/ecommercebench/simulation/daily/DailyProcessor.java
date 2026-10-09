package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.state.SimulationState;

/**
 * 每日结算流水线中的单一步骤。
 */
@FunctionalInterface
public interface DailyProcessor {
    void process(SimulationState state, DailyContext context, DailyResult result);
}
