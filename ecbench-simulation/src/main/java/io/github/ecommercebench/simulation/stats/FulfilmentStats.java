package io.github.ecommercebench.simulation.stats;

import io.github.ecommercebench.simulation.state.ShipSpeed;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 记录售出、已发货、取消及配送速度分布。
 */
public final class FulfilmentStats {

    private int ordersSold;
    private int unitsSold;
    private int ordersShipped;
    private int ordersCancelled;
    private final Map<ShipSpeed, Integer> shipSpeedCounts = new EnumMap<>(ShipSpeed.class);

    public void recordSold(int quantity) {
        ordersSold++;
        unitsSold += quantity;
    }

    public void recordShipped(ShipSpeed speed) {
        ordersShipped++;
        shipSpeedCounts.merge(speed, 1, Integer::sum);
    }

    public void recordCancelled() {
        ordersCancelled++;
    }

    public int ordersSold() {
        return ordersSold;
    }

    public int unitsSold() {
        return unitsSold;
    }

    public int ordersShipped() {
        return ordersShipped;
    }

    public int ordersCancelled() {
        return ordersCancelled;
    }

    public Map<ShipSpeed, Integer> shipSpeedCounts() {
        return Collections.unmodifiableMap(shipSpeedCounts);
    }
}
