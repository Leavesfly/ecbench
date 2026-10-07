package io.github.ecommercebench.app.run;

import io.github.ecommercebench.agent.RunResult;

/**
 * 单个 run 的执行结果。
 *
 * <p>{@code failure} 非空表示该 run 因未捕获异常终止（其余 run 不受影响）；成功时 {@code result} 携带 episode 结果。index 用于跨并行
 * run 稳定排序。
 */
public record RunOutcome(int index, RunResult result, Throwable failure) {

  public boolean succeeded() {
    return failure == null;
  }
}
