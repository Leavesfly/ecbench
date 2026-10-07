package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

/** 一项商品上架计划。 */
public record PublishItem(String productId, int quantity, Money retailPrice) {}
