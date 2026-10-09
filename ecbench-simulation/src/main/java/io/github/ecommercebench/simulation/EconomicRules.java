package io.github.ecommercebench.simulation;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.state.ShipSpeed;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 从 store_type_config.py 迁移的仿真常量。
 */
public final class EconomicRules {

    public static final int SETTLEMENT_WINDOW_DAYS = 9;
    public static final int SHIP_DEADLINE_DAYS = 2;
    public static final int BANKRUPTCY_DAYS = 10;
    public static final BigDecimal SALES_COMMISSION_RATE = new BigDecimal("0.02");
    public static final BigDecimal PER_STORE_SCALE = new BigDecimal("0.10");
    public static final BigDecimal CATEGORY_CAP_FRACTION = new BigDecimal("0.35");
    public static final BigDecimal LIQUIDATION_SALVAGE_RATE = new BigDecimal("0.10");
    public static final Money IDLE_DAILY_PENALTY = Money.of("1000");

    private static final Map<Integer, Money> OPS_COST =
            Map.of(1, Money.of("130"), 2, Money.of("100"), 3, Money.of("60"));

    private static final Map<String, SizeCost> SIZE_COSTS =
            Map.of(
                    "Small", new SizeCost(Money.of("0.50"), Money.of("0.05")),
                    "Medium", new SizeCost(Money.of("1.50"), Money.of("0.15")),
                    "Large", new SizeCost(Money.of("3.00"), Money.of("0.50")),
                    "XLarge", new SizeCost(Money.of("6.00"), Money.of("1.50")));

    private static final Map<ShipSpeed, ShippingRule> SHIPPING =
            Map.of(
                    ShipSpeed.FAST, new ShippingRule(new BigDecimal("2.0"), new BigDecimal("0.75")),
                    ShipSpeed.STANDARD, new ShippingRule(BigDecimal.ONE, BigDecimal.ONE),
                    ShipSpeed.SLOW, new ShippingRule(new BigDecimal("0.5"), new BigDecimal("1.30")));

    private static final Map<String, Double> MARKET_CAPACITY =
            Map.ofEntries(
                    Map.entry("appliance_digital", 13.959),
                    Map.entry("shoes_bags", 127.2),
                    Map.entry("home_living", 4218.18),
                    Map.entry("auto_hardware", 19862.16),
                    Map.entry("fashion", 224.37),
                    Map.entry("food_beverage", 658.56),
                    Map.entry("mother_baby", 44.0154),
                    Map.entry("daily_office", 1345.8),
                    Map.entry("beauty", 21.78),
                    Map.entry("sports_outdoor", 41.16),
                    Map.entry("pet", 91.2),
                    Map.entry("toys_entertainment", 23.28));

    private static final Map<String, Double> DEMAND_SCALE =
            Map.ofEntries(
                    Map.entry("appliance_digital", 0.6),
                    Map.entry("shoes_bags", 0.2),
                    Map.entry("home_living", 6.0),
                    Map.entry("auto_hardware", 4.0),
                    Map.entry("fashion", 4.0),
                    Map.entry("food_beverage", 9.0),
                    Map.entry("mother_baby", 0.8),
                    Map.entry("daily_office", 4.0),
                    Map.entry("beauty", 1.5),
                    Map.entry("sports_outdoor", 1.5),
                    Map.entry("pet", 0.75),
                    Map.entry("toys_entertainment", 1.5));

    private static final List<AgeMultiplier> STORAGE_AGE_MULTIPLIERS =
            List.of(
                    new AgeMultiplier(0, new BigDecimal("1.0")),
                    new AgeMultiplier(21, new BigDecimal("1.4")),
                    new AgeMultiplier(45, new BigDecimal("2.2")),
                    new AgeMultiplier(90, new BigDecimal("4.0")),
                    new AgeMultiplier(135, new BigDecimal("6.0")),
                    new AgeMultiplier(180, new BigDecimal("9.0")));

    private EconomicRules() {
    }

    public static Money operationsCost(int tier) {
        return OPS_COST.getOrDefault(tier, Money.of("80"));
    }

    public static SizeCost sizeCost(String size) {
        return SIZE_COSTS.getOrDefault(size, new SizeCost(Money.of("1"), Money.of("0.10")));
    }

    public static ShippingRule shipping(ShipSpeed speed) {
        return SHIPPING.get(speed);
    }

    public static double marketCapacity(String storeType) {
        return MARKET_CAPACITY.getOrDefault(storeType, 50.0);
    }

    public static double demandScale(String storeType) {
        return DEMAND_SCALE.getOrDefault(storeType, 1.0);
    }

    public static BigDecimal storageAgeMultiplier(int ageDays) {
        BigDecimal result = BigDecimal.ONE;
        for (AgeMultiplier item : STORAGE_AGE_MULTIPLIERS) {
            if (ageDays >= item.minimumAgeDays()) {
                result = item.multiplier();
            }
        }
        return result;
    }

    public record SizeCost(Money shipping, Money storagePerDay) {
    }

    public record ShippingRule(BigDecimal costMultiplier, BigDecimal returnMultiplier) {
    }

    private record AgeMultiplier(int minimumAgeDays, BigDecimal multiplier) {
    }
}
