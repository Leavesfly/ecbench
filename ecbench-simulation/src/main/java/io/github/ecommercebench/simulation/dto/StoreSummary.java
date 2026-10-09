package io.github.ecommercebench.simulation.dto;

/**
 * 店铺列表中的轻量摘要。
 */
public record StoreSummary(
        String storeId,
        String storeType,
        String storeName,
        boolean open,
        double reputation,
        int units) {
}
