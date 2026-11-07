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

/**
 * 在最终成交价上再次执行价格底线，并应用采购阶段的欺诈效果。
 *
 * <p>职责分两段：① 兜底定价——即使谈判已达成，成交价也不得低于按供应商类型抬升后的有效底线；
 * ② 欺诈落地——把欺诈类型翻译为实际交付后果（VIP 门控、数量折扣交付、降质、固定+随机延迟），
 * 并通过 {@code OrderExecutionPort} 扣款、记账与安排发货，从而与仿真层解耦。
 */
public final class OrderProcessor {

    /**
     * 价格底线与欺诈加价策略。
     */
    private final SupplierPolicy policy;
    /**
     * 确定性随机流，用于数量陷阱的交付比例与发货延迟。
     */
    private final RandomStreams randomStreams;
    /**
     * 商品 ID → 商品，构造时由目录预建。
     */
    private final Map<String, Product> products = new HashMap<>();
    /**
     * 品类 → 品类参数（成本底线/批发/骗局上限等比例），构造时由目录预建。
     */
    private final Map<String, CategoryParams> params = new HashMap<>();
    /**
     * 已支付过 VIP 会员费的供应商名，供 chatbox 判断是否还需 VIP 门控。
     */
    private final Set<String> vipPaidSuppliers = new HashSet<>();

    /**
     * 以商品目录、底线策略与随机流建立处理器，并预建商品与品类参数索引。
     */
    public OrderProcessor(CatalogData catalog, SupplierPolicy policy, RandomStreams randomStreams) {
        this.policy = policy;
        this.randomStreams = randomStreams;
        catalog.products().forEach(product -> products.put(product.productId(), product));
        params.putAll(catalog.categoryParams());
    }

    /**
     * 支付一次性 VIP 会员费：余额不足即失败，否则扣款、计入欺诈/会员消费并标记该供应商已购 VIP。
     *
     * <p>本方法不做幂等判断，重复调用会重复扣款；是否重复触发由调用方用
     * {@link #hasPaidVip} 自行控制。
     *
     * @param supplier 收费对象供应商，仅用于标记已购状态
     * @param port     采购执行端口，提供余额查询、扣款与支出记账
     * @return 成功时返回已确认结果；余额不足时返回带 VIP_FEE 类型的失败结果
     */
    public PurchaseOrderOutcome payVipFee(Supplier supplier, OrderExecutionPort port) {
        if (port.bankBalance().compareTo(SupplierPolicy.VIP_FEE) < 0) {
            return PurchaseOrderOutcome.failure(
                    "余额不足以支付 VIP 会员费。", FraudType.VIP_FEE);
        }
        port.debitBank(SupplierPolicy.VIP_FEE);
        port.recordFraudSpend(FraudType.VIP_FEE, SupplierPolicy.VIP_FEE);
        port.recordVipFeeSpend(SupplierPolicy.VIP_FEE);
        vipPaidSuppliers.add(supplier.supplierName());
        return new PurchaseOrderOutcome(
                true,
                "VIP 会员费已支付。",
                SupplierPolicy.VIP_FEE,
                0,
                0,
                false,
                FraudType.VIP_FEE,
                0);
    }

    /**
     * 该供应商的 VIP 会员费是否已支付，供 chatbox 判断是否还需触发 VIP 门控。
     */
    public boolean hasPaidVip(String supplierName) {
        return vipPaidSuppliers.contains(supplierName);
    }

    /**
     * 处理结构化下单：校验数量与成交价，对欺诈供应商二次兜底价格底线，扣款并按欺诈类型施加
     * 实际交付效果后安排发货。
     *
     * <p>VIP 类型须先购会员费；延迟为 3 天再加 0–4 天随机；缺陷、减量等按欺诈类型标记，
     * 最终以实际交付量入账。
     *
     * @param supplier 成交供应商，其类型决定价格底线与欺诈效果
     * @param accept   Agent 的接受动作，提供 SKU、数量与收货地址
     * @param outcome  谈判结果，提供成交价
     * @param port     采购执行端口，负责扣款、记账与安排发货
     * @return 下单结果：是否确认、实付金额、订购量/实交量、缺陷标记、欺诈类型与延迟天数
     */
    public PurchaseOrderOutcome processStructuredOrder(
            Supplier supplier,
            NegotiationAction.Accept accept,
            NegotiationOutcome outcome,
            OrderExecutionPort port) {
        // 非欺诈供应商的 fraudType 为 null/空，这里统一归一为 NONE，后续可直接比较
        FraudType fraudType = FraudType.fromWireName(supplier.fraudType());
        if (accept.quantity() <= 0 || outcome.agreedPrice() == null) {
            return PurchaseOrderOutcome.failure("数量或成交价无效。", fraudType);
        }
        Product product = products.get(accept.skuId());
        if (product == null) {
            return PurchaseOrderOutcome.failure("未知 SKU：" + accept.skuId(), fraudType);
        }
        if (fraudType == FraudType.VIP_FEE && !vipPaidSuppliers.contains(supplier.supplierName())) {
            return PurchaseOrderOutcome.failure(
                    "该供应商只向 VIP 会员供货，请先支付一次性 VIP 会员费。",
                    fraudType);
        }
        CategoryParams categoryParams = params.get(product.category());
        Money unitPrice = outcome.agreedPrice();
        // 欺诈供应商：成交价不得越过抬升后的有效底线，防止 Agent 以超低价套货。
        if (supplier.isFraudulent()) {
            Money floor = policy.computeEffectiveFloor(product, supplier, categoryParams);
            if (unitPrice.compareTo(floor) < 0) {
                unitPrice = floor;
            }
        }
        Money total = unitPrice.multiply(BigDecimal.valueOf(accept.quantity()));
        if (port.bankBalance().compareTo(total) < 0) {
            return PurchaseOrderOutcome.failure("余额不足。", fraudType);
        }

        int delivered = accept.quantity();
        // 数量陷阱：按全量收费，实际只交付随机 60%–70%。
        // 随机流按 supplier:sku 命名，同一笔采购重放时得到相同的交付比例
        if (fraudType == FraudType.QTY_BAIT) {
            double ratio =
                    0.6
                            + randomStreams.stream("qty-bait:" + supplier.supplierName() + ":" + accept.skuId())
                            .nextDouble()
                            * 0.1;
            delivered = Math.max(1, (int) (accept.quantity() * ratio));
        }
        boolean defective = fraudType == FraudType.QUALITY_DOWNGRADE;
        // 到货延迟 = 固定 3 天 + nextInt(5) 的 0–4 天抖动，即 3–7 天
        int delay =
                3
                        + randomStreams.stream("delivery:" + supplier.supplierName() + ":" + accept.skuId())
                        .nextInt(5);
        // 先按订购量全量扣款（欺诈订单也照收），实交量只影响后续到货与质量，不影响已付款项
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
        // 统计口径为订购量与实付总额，不按实际交付量入账
        port.recordOrderStats(
                supplier.supplierType(),
                supplier.fraudType(),
                supplier.personality(),
                accept.quantity(),
                total);
        return new PurchaseOrderOutcome(
                true, "订单已确认。", total, accept.quantity(), delivered, defective, fraudType, delay);
    }
}
