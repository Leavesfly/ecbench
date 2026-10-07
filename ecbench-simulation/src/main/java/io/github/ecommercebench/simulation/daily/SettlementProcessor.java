package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.state.SimulationState;

/** 将到期托管批次转入平台钱包。 */
public final class SettlementProcessor implements DailyProcessor {
  @Override
  public void process(SimulationState state, DailyContext context, DailyResult result) {
    result.setSettledToWallet(state.accounts().settleMatured(context.day()));
  }
}
