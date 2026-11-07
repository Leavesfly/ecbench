package io.github.ecommercebench.simulation.stats;

import io.github.ecommercebench.simulation.state.ShipSpeed;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 记录售出、已发货、取消及配送速度分布。
 *
 * <p>计数口径覆盖订单生命周期：成交时计 sold，发货时计 shipped，逾期未发货被系统取消时计 cancelled；
 * 指标层据此得出 on_time_ship_rate = ordersShipped / ordersSold。
 */
public final class FulfilmentStats {

    /**
     * 成交订单笔数：一条 SKU 需求行成交即计一笔，与件数无关。
     */
    private int ordersSold;

    /**
     * 成交商品件数：各笔订单数量之和，实际退货率等指标以它作分母。
     */
    private int unitsSold;

    /**
     * 已发货订单笔数：Agent 调用发货工具成功出货的订单数。
     */
    private int ordersShipped;

    /**
     * 被系统取消的订单笔数：超过发货截止日仍未发货。
     */
    private int ordersCancelled;

    /**
     * 已发货订单按配送速度的分布：FAST/STANDARD/SLOW → 笔数，仅含实际出现过的速度。
     */
    private final Map<ShipSpeed, Integer> shipSpeedCounts = new EnumMap<>(ShipSpeed.class);

    /**
     * 记录一笔成交订单及其件数，由 SalesProcessor 在需求成交、扣减库存后逐 SKU 调用（此时尚未发货）。
     *
     * @param quantity 本笔订单成交的商品件数
     */
    public void recordSold(int quantity) {
        ordersSold++;
        unitsSold += quantity;
    }

    /**
     * 记录一笔已发货订单，并按其配送速度累加分布计数；由 SimulationEngine 的发货流程对每个待发货单调用一次。
     *
     * @param speed 本批订单采用的配送速度，越快运费越高、退货率乘子越低
     */
    public void recordShipped(ShipSpeed speed) {
        ordersShipped++;
        shipSpeedCounts.merge(speed, 1, Integer::sum);
    }

    /**
     * 记录一笔被取消的订单，由 OverdueCancelProcessor 在订单超过发货截止日、库存退回仓库后调用。
     */
    public void recordCancelled() {
        ordersCancelled++;
    }

    public int ordersSold() {
        return ordersSold;
    }

    public int unitsSold() {
        return unitsSold;
    }

    public int ordersShipped() {
        return ordersShipped;
    }

    public int ordersCancelled() {
        return ordersCancelled;
    }

    /**
     * 已发货订单按配送速度的分布；返回不可修改视图，未出现过的速度不在键集中。
     */
    public Map<ShipSpeed, Integer> shipSpeedCounts() {
        return Collections.unmodifiableMap(shipSpeedCounts);
    }
}
