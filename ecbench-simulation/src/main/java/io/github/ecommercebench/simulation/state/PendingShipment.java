package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已售出但尚未由 Agent 发货的订单批次。
 *
 * @param revenueGross 含税售价毛收入
 * @param commission 平台销售佣金（毛收入的一部分）
 * @param revenueNet 毛收入扣除佣金后的净入，发货后进入托管
 * @param purchaseUnitPrice 单件采购成本（FIFO 加权），用于核算利润与退货重入库
 * @param deadline 发货截止日（saleDate + SHIP_DEADLINE_DAYS），逾期未发将被取消
 * @param baseReturnRate 当前商品的有效退货率（已含缺陷与定价修正）
 * @param returnRateBeforeDefect 未叠加缺陷修正前的退货率，供退货归因分解
 * @param returnRateAfterDefect 叠加缺陷修正后的退货率
 * @param returnRateAfterPrice 再叠加定价修正后的退货率
 * @param referencePrice 参考价，用于定价相关的退货与需求计算
 */
public record PendingShipment(
        long shipmentId,
        String storeId,
        String productId,
        int quantity,
        Money unitPrice,
        Money revenueGross,
        Money revenueNet,
        Money commission,
        Money purchaseUnitPrice,
        LocalDate saleDate,
        LocalDate deadline,
        BigDecimal baseReturnRate,
        BigDecimal returnRateBeforeDefect,
        BigDecimal returnRateAfterDefect,
        BigDecimal returnRateAfterPrice,
        BigDecimal referencePrice) {
}
