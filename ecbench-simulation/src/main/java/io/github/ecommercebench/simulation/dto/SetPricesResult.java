package io.github.ecommercebench.simulation.dto;

import java.util.List;

/**
 * 批量调价结果。
 */
public record SetPricesResult(String storeId, List<ItemOperationResult> results) {
    public SetPricesResult {
        results = List.copyOf(results);
    }
}
