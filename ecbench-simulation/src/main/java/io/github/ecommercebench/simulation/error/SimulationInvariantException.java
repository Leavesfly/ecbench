package io.github.ecommercebench.simulation.error;

/** 仿真内部状态违反不变量时抛出，表示实现错误而非用户操作错误。 */
public class SimulationInvariantException extends RuntimeException {
  public SimulationInvariantException(String message) {
    super(message);
  }
}
