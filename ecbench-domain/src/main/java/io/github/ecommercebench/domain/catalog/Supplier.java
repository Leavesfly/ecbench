package io.github.ecommercebench.domain.catalog;

import java.util.List;
import java.util.Objects;

/**
 * 供应商目录定义；fraudType 为空表示正常供应商。
 */
public record Supplier(
        String supplierId,
        String supplierName,
        String supplierEmail,
        String supplierType,
        String personality,
        double urgency,
        String fraudType,
        List<String> categoriesServed,
        int bankruptcyThreshold) {

    public Supplier {
        Objects.requireNonNull(supplierId, "supplierId 不能为空");
        Objects.requireNonNull(supplierType, "supplierType 不能为空");
        categoriesServed = List.copyOf(categoriesServed);
        fraudType = fraudType == null || fraudType.isBlank() ? null : fraudType;
    }

    public boolean isFraudulent() {
        return "bad".equals(supplierType);
    }
}
