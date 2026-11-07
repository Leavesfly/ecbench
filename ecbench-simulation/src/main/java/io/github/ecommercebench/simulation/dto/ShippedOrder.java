package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;

/**
 * 单笔发货明细，是 {@link ShipResult#shipments()} 的元素。
 *
 * <p>由 {@code SimulationEngine#shipOrders} 逐笔生成：发货时立即扣运费并把净收入放入托管，
 * 待托管到期后由 SettlementProcessor 结转（含可能的退货退款）。{@code ship_orders} 工具逐字段透出。
 *
 * @param shipmentId       待发货单号（即已移除的 PendingShipment.shipmentId）
 * @param productId        发货商品 SKU
 * @param quantity         本单发货件数，与运费、托管金额的乘数口径一致
 * @param shippingCost     本笔运费（尺寸档位价 × 配送速度乘子 × 件数），发货时已从银行扣除
 * @param revenueIntoEscrow 本笔放入托管的净收入（毛收入减平台佣金）
 * @param settlesOn        托管到期结算日 = 发货日 + SETTLEMENT_WINDOW_DAYS（9 天）
 */
public record ShippedOrder(
        long shipmentId,
        String productId,
        int quantity,
        Money shippingCost,
        Money revenueIntoEscrow,
        LocalDate settlesOn) {
}
