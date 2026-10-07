package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

/** 根据库存批次与库龄计算单件每日仓储费。 */
@FunctionalInterface
public interface StorageFeeRule {
  Money feePerItem(WarehouseLot lot, int ageDays);
}
