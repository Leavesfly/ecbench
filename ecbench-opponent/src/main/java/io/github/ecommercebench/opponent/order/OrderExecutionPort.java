package io.github.ecommercebench.opponent.order;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.FraudType;

/**
 * 隔离 opponent 与 simulation 的采购执行端口。
 */
public interface OrderExecutionPort {
    Money bankBalance();

    void debitBank(Money amount);

    void scheduleDelivery(ScheduledDelivery delivery);

    void recordFraudSpend(FraudType type, Money amount);

    /**
     * 记录一笔已确认订单的聚合统计（订单数/总支出/坑供应商订单/按类型件数/好供应商按人格支出）。
     *
     * <p>默认空实现：仅仿真适配器需要将其落入 {@code FraudStats}，测试替身可不关心。
     */
    default void recordOrderStats(
            String supplierType, String fraudType, String personality, int units, Money totalCost) {
    }

    /**
     * 记录一笔独立 VIP 会员费支出（计入总支出与 VIP 已付金额）。默认空实现。
     */
    default void recordVipFeeSpend(Money amount) {
    }
}
