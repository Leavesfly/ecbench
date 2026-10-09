package io.github.ecommercebench.simulation.dto;

import java.util.List;

/**
 * 搜索结果中可向 Agent 展示的供应商公开信息。
 */
public record SupplierView(
        String supplierName, String supplierEmail, List<String> categoriesServed) {
    public SupplierView {
        categoriesServed = List.copyOf(categoriesServed);
    }
}
