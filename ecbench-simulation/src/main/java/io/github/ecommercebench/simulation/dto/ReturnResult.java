package io.github.ecommercebench.simulation.dto;

import java.util.List;

/**
 * 店铺库存退回仓库的批量结果。
 */
public record ReturnResult(String storeId, List<ItemOperationResult> results) {
    public ReturnResult {
        results = List.copyOf(results);
    }
}
