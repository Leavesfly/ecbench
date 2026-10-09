package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.state.PendingReturn;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.StoreState;
import io.github.ecommercebench.simulation.state.WarehouseLot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;

/**
 * 处理已到达的退货：全额退款、商品回库，已付运费不返还。
 */
public final class ReturnProcessor implements DailyProcessor {
    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        int processed = 0;
        Iterator<PendingReturn> iterator = state.pendingReturns().iterator();
        while (iterator.hasNext()) {
            PendingReturn pending = iterator.next();
            if (!pending.arrivalDate().isAfter(context.day())) {
                Money refund = pending.refundPerUnit().multiply(BigDecimal.valueOf(pending.quantity()));
                Money shippingLoss =
                        pending.shippingCostPerUnit().multiply(BigDecimal.valueOf(pending.quantity()));
                state.accounts().refund(pending.escrowBatchId(), refund);
                state
                        .warehouse()
                        .addLot(
                                new WarehouseLot(
                                        pending.productId(),
                                        pending.quantity(),
                                        context.day(),
                                        pending.purchaseUnitPrice(),
                                        false));
                state.recordReturned(pending.productId(), pending.quantity());
                StoreState store = state.store(pending.storeId());
                if (store != null) {
                    store.recordReturn(pending.productId(), pending.quantity(), refund);
                }
                int defective =
                        BigDecimal.valueOf(pending.quantity())
                                .multiply(pending.defectiveFraction())
                                .setScale(0, RoundingMode.HALF_UP)
                                .intValue();
                state
                        .returnStats()
                        .recordActual(
                                pending.quantity(),
                                pending.quantity() - defective,
                                0,
                                0,
                                defective,
                                refund,
                                shippingLoss);
                iterator.remove();
                processed++;
            }
        }
        result.setReturnsProcessed(processed);
    }
}
