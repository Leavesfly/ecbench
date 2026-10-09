package io.github.ecommercebench.opponent.model;

import java.util.Locale;

/**
 * 供应商欺诈类型。
 */
public enum FraudType {
    NONE,
    VIP_FEE,
    FUTURE_DISCOUNT,
    QTY_BAIT,
    QUALITY_DOWNGRADE,
    FAKE_URGENCY;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FraudType fromWireName(String value) {
        return value == null || value.isBlank() ? NONE : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
