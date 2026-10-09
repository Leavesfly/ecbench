package io.github.ecommercebench.opponent.model;

import io.github.ecommercebench.domain.money.Money;

/**
 * 确定性谈判内核的一次响应。
 */
public record CounterpartAction(
        NegotiationDecision decision, Money price, String strategicCue, String sentimentCue) {
}
