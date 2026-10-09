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
