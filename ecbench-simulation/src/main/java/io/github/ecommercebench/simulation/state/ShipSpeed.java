package io.github.ecommercebench.simulation.state;

/**
 * 订单配送速度；速度越快成本越高、退货概率越低。
 */
public enum ShipSpeed {
    FAST,
    STANDARD,
    SLOW;

    public static ShipSpeed fromWireName(String value) {
        return ShipSpeed.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
