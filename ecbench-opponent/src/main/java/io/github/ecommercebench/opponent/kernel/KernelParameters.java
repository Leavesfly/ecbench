package io.github.ecommercebench.opponent.kernel;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.SupplierFamily;

/**
 * 单次 supplier/SKU 谈判内核的不可变参数。
 */
public record KernelParameters(
        SupplierFamily family,
        Money reservationPrice,
        double concessionWillingness,
        String stance,
        double openingHarshness,
        int maxRounds,
        Money minimumPrice,
        Money maximumPrice) {
}
