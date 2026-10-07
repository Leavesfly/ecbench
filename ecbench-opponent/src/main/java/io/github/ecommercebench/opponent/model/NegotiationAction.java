package io.github.ecommercebench.opponent.model;

import io.github.ecommercebench.domain.money.Money;

/** 从 chatbox 文本中的 negotiate JSON 块解析出的结构化动作。 */
public sealed interface NegotiationAction {

  String skuId();

  int quantity();

  record Offer(String skuId, Money price, int quantity) implements NegotiationAction {}

  record Accept(String skuId, Money price, int quantity, String shippingAddress)
      implements NegotiationAction {}

  record Reject(String skuId, int quantity) implements NegotiationAction {}
}
