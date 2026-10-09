package io.github.ecommercebench.simulation.dto;

/**
 * 一项按商品标识指定的数量。
 */
public record QtyItem(String productId, int quantity) {
}
