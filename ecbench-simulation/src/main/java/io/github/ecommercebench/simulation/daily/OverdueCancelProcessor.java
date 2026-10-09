package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.simulation.state.PendingShipment;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.StoreState;
import io.github.ecommercebench.simulation.state.WarehouseLot;

import java.util.Iterator;

/**
 * 取消超过发货截止日的订单，并把商品作为未发货库存退回仓库。
 */
public final class OverdueCancelProcessor implements DailyProcessor {
    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        int count = 0;
        Iterator<PendingShipment> iterator = state.pendingShipments().iterator();
        while (iterator.hasNext()) {
            PendingShipment shipment = iterator.next();
            // 截止日已早于今天：订单过期，按原采购价作为未发货(非劣质)批次退回仓库并计一次取消。
            if (shipment.deadline().isBefore(context.day())) {
                state
                        .warehouse()
                        .addLot(
                                new WarehouseLot(
                                        shipment.productId(),
                                        shipment.quantity(),
                                        context.day(),
                                        shipment.purchaseUnitPrice(),
                                        false));
                StoreState store = state.store(shipment.storeId());
                if (store != null) {
                    store.recordCancellation(shipment.quantity());
                }
                state.fulfilmentStats().recordCancelled();
                iterator.remove();
                count++;
            }
        }
        result.setOrdersCancelled(count);
        if (count > 0) {
            result.addNotification(
                    new SystemNotification(
                            "fulfilment",
                            context.day(),
                            count
                                    + " order(s) were CANCELLED because they were not shipped before the deadline."));
        }
    }
}
