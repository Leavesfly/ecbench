package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.state.SimulationState;

/**
 * 按物理库存批次的库龄收取仓储费。
 */
public final class StorageFeeProcessor implements DailyProcessor {

    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        Money total =
                state
                        .warehouse()
                        .calculateStorageFee(
                                context.day(),
                                (lot, ageDays) -> {
                                    Product product = context.products().get(lot.sku());
                                    String size = product == null ? "Small" : product.size();
                                    return EconomicRules.sizeCost(size)
                                            .storagePerDay()
                                            .multiply(EconomicRules.storageAgeMultiplier(ageDays));
                                });
        if (!total.isZero()) {
            state.accounts().chargeBank(total);
        }
        result.setStorageCharged(total);
    }
}
