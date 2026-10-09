package io.github.ecommercebench.agent.chat;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.FraudType;
import io.github.ecommercebench.opponent.order.OrderExecutionPort;
import io.github.ecommercebench.opponent.order.ScheduledDelivery;
import io.github.ecommercebench.simulation.SimulationEngine;

/**
 * 把 opponent 的 {@link OrderExecutionPort} 适配到 {@link SimulationEngine}。
 *
 * <p>只做委托，不复制任何业务规则：余额查询/扣款走账户，到货走引擎的采购收货（延迟>0 时进入待到货队列）， 欺诈支出计入仿真统计。
 */
public final class SimulationOrderExecutionAdapter implements OrderExecutionPort {

    private final SimulationEngine engine;

    public SimulationOrderExecutionAdapter(SimulationEngine engine) {
        this.engine = engine;
    }

    @Override
    public Money bankBalance() {
        return engine.state().accounts().bank();
    }

    @Override
    public void debitBank(Money amount) {
        engine.state().accounts().chargeBank(amount);
    }

    @Override
    public void scheduleDelivery(ScheduledDelivery delivery) {
        engine.receivePurchaseOrder(
                delivery.supplierName(),
                delivery.skuId(),
                delivery.quantity(),
                delivery.unitPrice(),
                delivery.defective(),
                delivery.deliveryDelayDays());
    }

    @Override
    public void recordFraudSpend(FraudType type, Money amount) {
        engine.state().fraudStats().recordSpend(type.wireName(), amount);
        if (type == FraudType.VIP_FEE) {
            engine.state().fraudStats().recordVipFee();
        }
    }

    @Override
    public void recordOrderStats(
            String supplierType, String fraudType, String personality, int units, Money totalCost) {
        engine
                .state()
                .fraudStats()
                .recordOrder("bad".equals(supplierType), fraudType, personality, units, totalCost);
    }

    @Override
    public void recordVipFeeSpend(Money amount) {
        engine.state().fraudStats().recordVipSpend(amount);
    }
}
