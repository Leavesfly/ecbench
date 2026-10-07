package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.state.SimulationState;

/** 连续十天银行余额为负时终止仿真。 */
public final class BankruptcyProcessor implements DailyProcessor {
  @Override
  public void process(SimulationState state, DailyContext context, DailyResult result) {
    state.accounts().updateNegativeStreak();
    if (state.accounts().consecutiveNegativeDays() >= EconomicRules.BANKRUPTCY_DAYS) {
      state.terminate("bankrupt");
      result.setBankrupt(true);
    }
  }
}
