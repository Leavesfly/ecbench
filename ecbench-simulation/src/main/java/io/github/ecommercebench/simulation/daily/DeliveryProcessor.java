package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.state.PendingDelivery;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.WarehouseLot;

import java.util.Iterator;

/**
 * 将到达时间不晚于当前日的采购订单加入仓库。
 */
public final class DeliveryProcessor implements DailyProcessor {
    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        int count = 0;
        Iterator<PendingDelivery> iterator = state.pendingDeliveries().iterator();
        while (iterator.hasNext()) {
            PendingDelivery delivery = iterator.next();
            // 到达日不晚于今天则入仓（作为新 FIFO 批次并记录劣质标记），否则留在待到货队列。
            if (!delivery.arrivalDate().isAfter(context.day())) {
                state
                        .warehouse()
                        .addLot(
                                new WarehouseLot(
                                        delivery.productId(),
                                        delivery.quantity(),
                                        context.day(),
                                        delivery.unitPrice(),
                                        delivery.defective()));
                state.recordDelivery(
                        delivery.supplierId(), delivery.productId(), delivery.quantity(), delivery.defective());
                iterator.remove();
                count++;
            }
        }
        result.setDeliveriesArrived(count);
    }
}
