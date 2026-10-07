package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

/** 一项调价请求。 */
public record PriceItem(String productId, Money price) {}
