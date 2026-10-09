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
            // 仅处理到达日不晚于今天的退货；未到期的留在队列等后续日切。
            if (!pending.arrivalDate().isAfter(context.day())) {
                Money refund = pending.refundPerUnit().multiply(BigDecimal.valueOf(pending.quantity()));
                Money shippingLoss =
                        pending.shippingCostPerUnit().multiply(BigDecimal.valueOf(pending.quantity()));
                // 从对应托管批次全额退款（已付运费不返还），商品按原采购价重新入仓。
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
                // 按退货时携带的缺陷占比四舍五入拆出缺陷件数，用于后续退货归因统计。
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
