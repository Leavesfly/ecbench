package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;

/**
 * 已付款、等待送达仓库的采购批次（待到货队列中的一项）。
 *
 * <p>由 {@code SimulationEngine#receivePurchaseOrder} 在到货延迟大于 0 时入队；
 * DeliveryProcessor 在到达日把它转成仓库 FIFO 批次（入仓日取实际到货日）并出队，同时记到货与缺陷统计。
 *
 * @param deliveryId  到货单号，由 state.nextDeliveryId() 递增分配
 * @param supplierId  供货供应商标识（未指定供应商时归一为 "unknown"，仍用于供应商溯源统计）
 * @param productId   采购商品 SKU
 * @param quantity    实交件数（数量陷阱会缩量，小于已付款的订购量）
 * @param unitPrice   成交单价，入仓时作为该批次的采购成本单价
 * @param arrivalDate 到货日 = 下单日 + 延迟天数，到达后由 DeliveryProcessor 入仓
 * @param defective   是否为劣质批次（质量 downgrade），影响缺陷占比与退货率修正
 */
public record PendingDelivery(
        long deliveryId,
        String supplierId,
        String productId,
        int quantity,
        Money unitPrice,
        LocalDate arrivalDate,
        boolean defective) {
}
