package io.github.ecommercebench.agent;

import io.github.ecommercebench.agent.tool.ToolExecutionResult;
import io.github.ecommercebench.llm.model.ChatMessage;
import java.util.List;

/**
 * Agent 主循环的可观测端口；由 Plan 05 的文件日志器/指标器实现。
 *
 * <p>所有方法均为默认空实现，观察者按需覆盖，主循环不依赖任何具体实现，也不因观察失败而中断。
 */
public interface RunObserver {

  /** episode 开始，携带本次运行的 job 规格。 */
  default void onRunStart(RunJob job) {}

  /** 每一轮（一次模型生成）开始，turn 从 1 起。 */
  default void onTurnStart(int turn) {}

  /** 模型返回的 assistant 消息（可能携带工具调用）。 */
  default void onAssistantMessage(ChatMessage message) {}

  /** 一批工具执行完成后的结果。 */
  default void onToolResults(List<ToolExecutionResult> results) {}

  /** 上下文被裁剪（清除最旧工具组）时触发，freed 为本次释放的 token 数。 */
  default void onContextTruncation(int turn, int tokensFreed) {}

  /** episode 正常结束并产出结果。 */
  default void onRunComplete(RunResult result) {}

  /** episode 因未捕获异常终止；随后异常仍会向上抛出由上层隔离。 */
  default void onFailure(Throwable error) {}
}
