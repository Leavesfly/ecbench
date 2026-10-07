package io.github.ecommercebench.opponent.model;

import io.github.ecommercebench.domain.money.Money;

/** KernelManager 暴露给 Chatbox 的结构化谈判结果。 */
public record NegotiationOutcome(
    String skuId,
    String productName,
    NegotiationDecision decision,
    Money price,
    Money agreedPrice,
    int round,
    String sentimentCue,
    String strategicCue,
    String errorCode,
    String error,
    boolean pendingAgreement) {

  public static NegotiationOutcome error(String sku, String code, String message, int round) {
    return new NegotiationOutcome(
        sku,
        sku,
        NegotiationDecision.ERROR,
        null,
        null,
        round,
        "neutral",
        "Concede",
        code,
        message,
        false);
  }
}
