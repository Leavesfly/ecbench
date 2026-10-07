package io.github.ecommercebench.opponent.model;

/** supplier/SKU 会话的生命周期状态。 */
public enum NegotiationState {
  ACTIVE,
  PENDING_ORDER,
  COMPLETED,
  REJECTED
}
