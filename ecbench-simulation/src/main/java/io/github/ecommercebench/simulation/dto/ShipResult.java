package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.state.ShipSpeed;

import java.util.List;

/**
 * 批量发货结果：一次 ship_orders 调用对多笔待发货订单的汇总。
 *
 * <p>成功时 speed/shipments 及各金额字段均有意义；失败时只有 error 与 bankBalance/pendingSettlement
 * 账户快照可用（见 {@link #failure}），此时 speed 为 null、shipments 为空。
 *
 * @param success                是否成功
 * @param speed                  本批采用的配送速度（失败时为 null）
 * @param shippedCount           实际发货的订单笔数
 * @param totalShippingCost      本批从银行扣除的运费合计
 * @param totalRevenueIntoEscrow 本批放入托管的净收入合计（毛收入减平台佣金）
 * @param bankBalance            扣运费后的银行余额快照
 * @param pendingSettlement      当前全部未到期托管的合计（含历史批次，非本批新增量）
 * @param shipments              逐单发货明细
 * @param error                  失败原因，成功时为 null
 */
public record ShipResult(
        boolean success,
        ShipSpeed speed,
        int shippedCount,
        Money totalShippingCost,
        Money totalRevenueIntoEscrow,
        Money bankBalance,
        Money pendingSettlement,
        List<ShippedOrder> shipments,
        String error) {

    /**
     * 紧凑构造器：把 null 明细归一为空列表，调用方无需判空。
     */
    public ShipResult {
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
    }

    /**
     * 构造失败结果：固定零发货、零金额、空明细与 null 速度，仅回填错误与账户快照。
     */
    public static ShipResult failure(String error, Money bank, Money escrow) {
        return new ShipResult(false, null, 0, Money.ZERO, Money.ZERO, bank, escrow, List.of(), error);
    }
}
