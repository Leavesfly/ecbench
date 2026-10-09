package io.github.ecommercebench.simulation.dto;

import java.util.List;

/**
 * 退货来源查询结果。
 */
public record ReturnTrace(List<ReturnTraceRow> products, String error) {
    public ReturnTrace {
        products = products == null ? List.of() : List.copyOf(products);
    }
}
