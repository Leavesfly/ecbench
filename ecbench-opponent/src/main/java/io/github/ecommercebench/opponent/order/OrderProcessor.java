package io.github.ecommercebench.opponent.order;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.config.SupplierPolicy;
import io.github.ecommercebench.opponent.model.FraudType;
import io.github.ecommercebench.opponent.model.NegotiationAction;
import io.github.ecommercebench.opponent.model.NegotiationOutcome;
import io.github.ecommercebench.opponent.model.PurchaseOrderOutcome;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 在最终成交价上再次执行价格底线，并应用采购阶段的欺诈效果。 */
public final class OrderProcessor {

  private final SupplierPolicy policy;
  private final RandomStreams randomStreams;
  private final Map<String, Product> products = new HashMap<>();
  private final Map<String, CategoryParams> params = new HashMap<>();
  private final Set<String> vipPaidSuppliers = new HashSet<>();

  public OrderProcessor(CatalogData catalog, SupplierPolicy policy, RandomStreams randomStreams) {
    this.policy = policy;
    this.randomStreams = randomStreams;
    catalog.products().forEach(product -> products.put(product.productId(), product));
    params.putAll(catalog.categoryParams());
  }

  public PurchaseOrderOutcome payVipFee(Supplier supplier, OrderExecutionPort port) {
    if (port.bankBalance().compareTo(SupplierPolicy.VIP_FEE) < 0) {
      return PurchaseOrderOutcome.failure(
          "Insufficient funds for VIP membership fee.", FraudType.VIP_FEE);
    }
    port.debitBank(SupplierPolicy.VIP_FEE);
    port.recordFraudSpend(FraudType.VIP_FEE, SupplierPolicy.VIP_FEE);
    port.recordVipFeeSpend(SupplierPolicy.VIP_FEE);
    vipPaidSuppliers.add(supplier.supplierName());
    return new PurchaseOrderOutcome(
        true,
        "VIP membership fee paid.",
        SupplierPolicy.VIP_FEE,
        0,
        0,
        false,
        FraudType.VIP_FEE,
        0);
  }

  /** 该供应商的 VIP 会员费是否已支付，供 chatbox 判断是否还需触发 VIP 门控。 */
  public boolean hasPaidVip(String supplierName) {
    return vipPaidSuppliers.contains(supplierName);
  }

  public PurchaseOrderOutcome processStructuredOrder(
      Supplier supplier,
      NegotiationAction.Accept accept,
      NegotiationOutcome outcome,
      OrderExecutionPort port) {
    FraudType fraudType = FraudType.fromWireName(supplier.fraudType());
    if (accept.quantity() <= 0 || outcome.agreedPrice() == null) {
      return PurchaseOrderOutcome.failure("Invalid quantity or agreed price.", fraudType);
    }
    Product product = products.get(accept.skuId());
    if (product == null) {
      return PurchaseOrderOutcome.failure("Unknown SKU: " + accept.skuId(), fraudType);
    }
    if (fraudType == FraudType.VIP_FEE && !vipPaidSuppliers.contains(supplier.supplierName())) {
      return PurchaseOrderOutcome.failure(
          "This supplier only sells to VIP members. Pay the one-time VIP membership fee first.",
          fraudType);
    }
    CategoryParams categoryParams = params.get(product.category());
    Money unitPrice = outcome.agreedPrice();
    if (supplier.isFraudulent()) {
      Money floor = policy.computeEffectiveFloor(product, supplier, categoryParams);
      if (unitPrice.compareTo(floor) < 0) {
        unitPrice = floor;
      }
    }
    Money total = unitPrice.multiply(BigDecimal.valueOf(accept.quantity()));
    if (port.bankBalance().compareTo(total) < 0) {
      return PurchaseOrderOutcome.failure("Insufficient funds.", fraudType);
    }

    int delivered = accept.quantity();
    if (fraudType == FraudType.QTY_BAIT) {
      double ratio =
          0.6
              + randomStreams.stream("qty-bait:" + supplier.supplierName() + ":" + accept.skuId())
                      .nextDouble()
                  * 0.1;
      delivered = Math.max(1, (int) (accept.quantity() * ratio));
    }
    boolean defective = fraudType == FraudType.QUALITY_DOWNGRADE;
    int delay =
        3
            + randomStreams.stream("delivery:" + supplier.supplierName() + ":" + accept.skuId())
                .nextInt(5);
    port.debitBank(total);
    if (supplier.isFraudulent()) {
      port.recordFraudSpend(fraudType, total);
    }
    port.scheduleDelivery(
        new ScheduledDelivery(
            supplier.supplierName(),
            accept.skuId(),
            delivered,
            unitPrice,
            defective,
            delay,
            accept.shippingAddress()));
    port.recordOrderStats(
        supplier.supplierType(),
        supplier.fraudType(),
        supplier.personality(),
        accept.quantity(),
        total);
    return new PurchaseOrderOutcome(
        true, "Order confirmed.", total, accept.quantity(), delivered, defective, fraudType, delay);
  }
}
