package io.github.ecommercebench.simulation;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.state.ShipSpeed;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 从 store_type_config.py 迁移的仿真常量。
 *
 * <p>集中存放结算/发货/破产窗口、佣金、市场容量等标尺，并提供带默认值的查表方法， 保证同一组经济参数被各日级处理器一致引用。
 */
public final class EconomicRules {

    /** 销售完成后托管资金的结算窗口天数（发货后若干天才回扰银行）。 */
    public static final int SETTLEMENT_WINDOW_DAYS = 9;
    /** 待发货订单的发货截止时间（超期未发会被逾期处理器取消）。 */
    public static final int SHIP_DEADLINE_DAYS = 2;
    /** 银行余额连续为负达到该天数则判定破产。 */
    public static final int BANKRUPTCY_DAYS = 10;
    /** 平台销售佣金率。 */
    public static final BigDecimal SALES_COMMISSION_RATE = new BigDecimal("0.02");
    /** 单店相对全市场的需求缩放系数。 */
    public static final BigDecimal PER_STORE_SCALE = new BigDecimal("0.10");
    /** 单一类目可占用的市场容量上限比例。 */
    public static final BigDecimal CATEGORY_CAP_FRACTION = new BigDecimal("0.35");
    /** 闭店清算时按采购成本回收的残值率。 */
    public static final BigDecimal LIQUIDATION_SALVAGE_RATE = new BigDecimal("0.10");
    /** 店铺未开业时的每日闲置惩罚。 */
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

    /** 按店铺层级返回每日运营固定成本，未知层级回退为 80。 */
    public static Money operationsCost(int tier) {
        return OPS_COST.getOrDefault(tier, Money.of("80"));
    }

    /** 按商品尺寸返回单件的运费与每日仓储费，未知尺寸回退为 Medium 基准。 */
    public static SizeCost sizeCost(String size) {
        return SIZE_COSTS.getOrDefault(size, new SizeCost(Money.of("1"), Money.of("0.10")));
    }

    /** 按配送速度返回运费与退货率乘子（快运贵但退货少，慢运便宜但退货多）。 */
    public static ShippingRule shipping(ShipSpeed speed) {
        return SHIPPING.get(speed);
    }

    /** 该店铺类型的市场容量（用于饱和与类目封顶），未知回退 50.0。 */
    public static double marketCapacity(String storeType) {
        return MARKET_CAPACITY.getOrDefault(storeType, 50.0);
    }

    /** 该店铺类型的日需求倍率，未知回退 1.0。 */
    public static double demandScale(String storeType) {
        return DEMAND_SCALE.getOrDefault(storeType, 1.0);
    }

    /** 根据库龄天数返回仓储费乘子（取阶梯中不超过当前库龄的最高一档，越久越贵）。 */
    public static BigDecimal storageAgeMultiplier(int ageDays) {
        BigDecimal result = BigDecimal.ONE;
        for (AgeMultiplier item : STORAGE_AGE_MULTIPLIERS) {
            if (ageDays >= item.minimumAgeDays()) {
                result = item.multiplier();
            }
        }
        return result;
    }

    /** 单件尺寸对应的运费与每日仓储费。 */
    public record SizeCost(Money shipping, Money storagePerDay) {
    }

    /** 配送速度对运费和退货率的乘性影响。 */
    public record ShippingRule(BigDecimal costMultiplier, BigDecimal returnMultiplier) {
    }

    /** 库龄阶梯：达到 minimumAgeDays 后适用 multiplier。 */
    private record AgeMultiplier(int minimumAgeDays, BigDecimal multiplier) {
    }
}
