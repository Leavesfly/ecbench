package io.github.ecommercebench.simulation.dto;

import java.util.List;

/**
 * 批量上架结果。
 */
public record PublishResult(String storeId, List<ItemOperationResult> results) {
    public PublishResult {
        results = List.copyOf(results);
    }
}
