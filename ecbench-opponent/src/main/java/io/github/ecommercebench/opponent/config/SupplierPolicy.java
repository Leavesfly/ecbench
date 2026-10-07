package io.github.ecommercebench.opponent.config;

import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.FraudType;
import java.math.BigDecimal;

/** 供应商价格底线与欺诈加价策略的单一实现。 */
public final class SupplierPolicy {

  public static final int MAX_ROUNDS = 10;
  public static final Money VIP_FEE = Money.of("1000");
  public static final BigDecimal QUALITY_DOWNGRADE_RETURN_FLOOR = new BigDecimal("0.40");

  public Money computeEffectiveFloor(Product product, Supplier supplier, CategoryParams params) {
    Money honestFloor = new Money(product.referencePrice().multiply(params.costFloorRatio()));
    if (!supplier.isFraudulent()) {
      return honestFloor;
    }
    FraudType type = FraudType.fromWireName(supplier.fraudType());
    BigDecimal multiplier =
        switch (type) {
          case VIP_FEE, FUTURE_DISCOUNT, FAKE_URGENCY -> new BigDecimal("1.5");
          default -> BigDecimal.ONE;
        };
    Money raised = honestFloor.multiply(multiplier);
    Money scamCap = new Money(product.referencePrice().multiply(params.scamCapRatio()));
    Money initialOffer = new Money(product.referencePrice().multiply(params.wholesaleRatio()));
    Money capped = min(raised, min(scamCap, initialOffer));
    return max(honestFloor, capped);
  }

  private Money min(Money first, Money second) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  private Money max(Money first, Money second) {
    return first.compareTo(second) >= 0 ? first : second;
  }
}
