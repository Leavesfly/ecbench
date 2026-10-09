package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.state.SimulationState;

/**
 * 每日结算流水线中的单一步骤。
 */
@FunctionalInterface
public interface DailyProcessor {
    /**
     * 处理一个日切步骤：从 context 读取静态目录与随机流，就地修改 state 的账户/仓库/待处理队列， 并将本步发生的业务量累计写入 result。
     */
    void process(SimulationState state, DailyContext context, DailyResult result);
}
