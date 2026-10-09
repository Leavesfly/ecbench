package io.github.ecommercebench.app.run;

import io.github.ecommercebench.agent.EcommerceBenchAgent;
import io.github.ecommercebench.agent.RunJob;
import io.github.ecommercebench.app.log.CompositeRunObserver;
import io.github.ecommercebench.simulation.SimulationEngine;

/**
 * 单次 run 的全部隔离组件束。
 *
 * <p>由 {@link RunComponentFactory} 为每个 run index 新建：独立的 {@code engine} 与 {@code agent}、写兼容产物的
 * {@code observer}， 以及可选的预构建 {@code job}（为 null 时 agent 使用其 defaultJob）。close 关闭观察者持有的全部文件写入器。
 */
public record RunComponents(
        SimulationEngine engine, EcommerceBenchAgent agent, CompositeRunObserver observer, RunJob job)
        implements AutoCloseable {

    @Override
    public void close() {
        observer.close();
    }
}
