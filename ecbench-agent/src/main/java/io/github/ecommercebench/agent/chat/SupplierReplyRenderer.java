package io.github.ecommercebench.agent.chat;

import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.opponent.model.NegotiationOutcome;
import java.util.List;

/**
 * 生成供应商（NPC）对 Agent 消息的回复文本。
 *
 * <p>把 LLM 调用抽象成端口，便于在协调器测试中用确定性 fake 替换；真实实现见 {@link LlmSupplierReplyRenderer}。
 */
@FunctionalInterface
public interface SupplierReplyRenderer {

  String render(Request request);

  /** 渲染一条供应商回复所需的全部上下文。 */
  record Request(
      Supplier supplier,
      String agentEmail,
      String conversationalContent,
      List<NegotiationOutcome> kernelResponses,
      List<DealRecord> dealHistory,
      String timestamp) {

    public Request {
      kernelResponses = kernelResponses == null ? List.of() : List.copyOf(kernelResponses);
      dealHistory = dealHistory == null ? List.of() : List.copyOf(dealHistory);
    }
  }

  /** 一条历史成交邮件记录，用于 NPC 提示词的 “Previous Dealings” 段。 */
  record DealRecord(String from, String to, String content) {}
}
