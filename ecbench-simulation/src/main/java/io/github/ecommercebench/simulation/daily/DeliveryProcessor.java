package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.state.PendingDelivery;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.WarehouseLot;

import java.util.Iterator;

/**
 * 将到达时间不晚于当前日的采购订单加入仓库。
 *
 * <p>消费待到货队列：到达日不晚于当日的批次入仓并出队，未到期的留待后续日切；入仓同时把到货量记账到
 * 到货/溯源/缺陷统计，最后把本日到货批次总数写入 DailyResult 快照（不额外推系统通知）。
 */
public final class DeliveryProcessor implements DailyProcessor {

    /**
     * 扫一遍待到货队列：满足到达条件的批次入仓、记账并出队，其余保留；本日到货批数写入 result.deliveriesArrived。
     */
    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        int count = 0;
        Iterator<PendingDelivery> iterator = state.pendingDeliveries().iterator();
        while (iterator.hasNext()) {
            PendingDelivery delivery = iterator.next();
            // 到达日不晚于今天则入仓（作为新 FIFO 批次并记录劣质标记），否则留在待到货队列。
            if (!delivery.arrivalDate().isAfter(context.day())) {
                // 入库日取当日（实际到货日），作为仓储费库龄起点；批次追加到该 SKU 的 FIFO 队尾。
                state
                        .warehouse()
                        .addLot(
                                new WarehouseLot(
                                        delivery.productId(),
                                        delivery.quantity(),
                                        context.day(),
                                        delivery.unitPrice(),
                                        delivery.defective()));
                // 到货记账：累计 SKU 到货量、供应商溯源量与缺陷量；缺陷占比只统计已到货批次，故记账放在入仓这一刻。
                state.recordDelivery(
                        delivery.supplierId(), delivery.productId(), delivery.quantity(), delivery.defective());
                // 已入仓批次出队，未到期批次保留，避免后续日切重复入仓。
                iterator.remove();
                count++;
            }
        }
        // 到货不推送系统通知，仅把本日到货批数写入当日摘要。
        result.setDeliveriesArrived(count);
    }
}
