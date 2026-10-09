package io.github.ecommercebench.app.run;

import io.github.ecommercebench.app.config.BenchmarkOptions;

/**
 * 执行单个 run 的接缝。
 *
 * <p>生产实现由 {@link RunCoordinator} 用 {@link RunComponentFactory} 装配组件并驱动 agent；测试可替换为受控实现以验证并行编排、
 * 失败隔离与排序，而不需要真实 LLM 或仿真。
 */
@FunctionalInterface
public interface RunExecutor {

    RunOutcome execute(int index, BenchmarkOptions options) throws Exception;
}
