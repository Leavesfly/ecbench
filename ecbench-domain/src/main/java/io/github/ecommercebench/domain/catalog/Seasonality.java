package io.github.ecommercebench.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 从 Python store_type_config.py 迁移的每月季节性系数。
 */
final class Seasonality {

    private static final Map<String, List<BigDecimal>> VALUES =
            Map.ofEntries(
                    entry("appliance_digital", "1.0,0.9,1.0,1.0,1.0,1.3,0.9,1.0,1.0,1.0,1.4,1.1"),
                    entry("fashion", "0.75,0.7,1.0,0.95,0.85,1.0,0.8,0.8,0.95,0.85,1.55,0.85"),
                    entry("shoes_bags", "0.9,0.8,1.1,1.1,1.0,1.2,0.9,0.9,1.0,1.0,1.4,1.0"),
                    entry("food_beverage", "1.3,1.6,1.0,0.9,0.9,0.8,1.1,1.1,1.3,0.9,0.6,1.5"),
                    entry("beauty", "1.0,1.0,1.3,1.0,1.0,1.2,1.0,1.0,1.0,1.0,1.3,1.0"),
                    entry("sports_outdoor", "0.8,0.8,1.0,1.2,1.3,1.2,1.3,1.2,1.0,1.0,1.0,0.8"),
                    entry("mother_baby", "1.0,1.0,1.0,1.0,1.1,1.2,1.0,1.0,1.1,1.0,1.2,1.0"),
                    entry("home_living", "1.0,0.9,1.1,1.1,1.0,1.2,1.0,1.0,1.0,1.0,1.3,1.0"),
                    entry("daily_office", "1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.1,1.2,1.0,1.0,1.0"),
                    entry("pet", "1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.1,1.0"),
                    entry("auto_hardware", "1.0,0.9,1.1,1.1,1.0,1.0,1.0,1.0,1.0,1.0,1.1,1.0"),
                    entry("toys_entertainment", "1.2,1.0,1.0,1.0,1.1,1.2,1.1,1.0,1.0,1.0,1.7,1.3"));

    private Seasonality() {
    }

    static List<BigDecimal> forStoreType(String storeType) {
        List<BigDecimal> values = VALUES.get(storeType);
        if (values == null) {
            throw new IllegalArgumentException("未知店铺类型的季节性配置: " + storeType);
        }
        return values;
    }

    private static Map.Entry<String, List<BigDecimal>> entry(String key, String values) {
        List<BigDecimal> parsed = List.of(values.split(",")).stream().map(BigDecimal::new).toList();
        return Map.entry(key, parsed);
    }
}
