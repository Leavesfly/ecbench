package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.catalog.CategoryParams;
import io.github.ecommercebench.domain.catalog.MarketEvent;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.PromotionConfig;
import io.github.ecommercebench.domain.catalog.PromotionPeriod;
import io.github.ecommercebench.domain.catalog.StoreTypeConfig;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.DeterministicRandom;
import io.github.ecommercebench.simulation.EconomicRules;
import io.github.ecommercebench.simulation.demand.DemandModel;
import io.github.ecommercebench.simulation.error.SimulationInvariantException;
import io.github.ecommercebench.simulation.state.PendingShipment;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.StoreState;
import io.github.ecommercebench.simulation.state.WarehouseConsumption;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根据价格、季节、活动、事件、声誉与容量计算每日销量并创建待发货批次。
 */
public final class SalesProcessor implements DailyProcessor {

    @Override
    public void process(SimulationState state, DailyContext context, DailyResult result) {
        LocalDate saleDate = context.day().minusDays(1);
        int totalSold = 0;
        for (StoreState store : state.stores().values()) {
            if (!store.isOpen()) {
                continue;
            }
            store.clearYesterdaySales();
            totalSold += processStore(state, context, store, saleDate);
        }
        result.setTotalSold(totalSold);
    }

    private int processStore(
            SimulationState state, DailyContext context, StoreState store, LocalDate saleDate) {
        DemandModel.PromotionBoost promotion = promotionBoost(context, store, saleDate);
        double eventFactor = eventDemandFactor(context, store.storeType(), saleDate);
        StoreTypeConfig storeType = context.catalog().storeTypes().get(store.storeType());
        double seasonality = storeType.seasonality().get(saleDate.getMonthValue() - 1).doubleValue();
        double weekdayFactor = isWeekend(saleDate) ? 1.3 : 1.0;

        Map<String, DemandLine> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> inventory : store.inventory().entrySet()) {
            String sku = inventory.getKey();
            Money price = store.prices().get(sku);
            Product product = context.products().get(sku);
            CategoryParams params =
                    product == null ? null : context.catalog().categoryParams().get(product.category());
            if (product == null || params == null || price == null || inventory.getValue() <= 0) {
                continue;
            }
            double effectivePrice =
                    price.amount().doubleValue() * (1.0 - activeDiscount(store, context, saleDate));
            double priceFactor =
                    context
                            .demandModel()
                            .priceFactor(
                                    params.elasticityType(),
                                    effectivePrice,
                                    product.referencePrice().doubleValue(),
                                    params.elasticityParam().doubleValue(),
                                    promotion.elasticityBoost());
            double baseMonthly = (params.monthlySalesMin() + params.monthlySalesMax()) / 2.0;
            double baseDaily =
                    baseMonthly
                            / 30.0
                            * EconomicRules.PER_STORE_SCALE.doubleValue()
                            * EconomicRules.demandScale(store.storeType());
            double demand =
                    Math.max(
                            0.0,
                            baseDaily
                                    * priceFactor
                                    * weekdayFactor
                                    * promotion.demandMultiplier()
                                    * seasonality
                                    * eventFactor
                                    * store.reputation());
            raw.put(
                    sku,
                    new DemandLine(product, params, inventory.getValue(), Money.of(effectivePrice), demand));
        }

        applyCategoryCap(raw, storeType);
        double rawTotal = raw.values().stream().mapToDouble(DemandLine::demand).sum();
        double saturation =
                context
                        .demandModel()
                        .marketSaturationFactor(EconomicRules.marketCapacity(store.storeType()), rawTotal);

        int sold = 0;
        for (Map.Entry<String, DemandLine> entry : raw.entrySet()) {
            DemandLine line = entry.getValue();
            double demand = line.demand() * saturation;
            int floor = (int) demand;
            DeterministicRandom rng =
                    context.randomStreams().stream("demand:" + entry.getKey() + ":" + saleDate);
            int rounded = floor + (rng.nextDouble() < demand - floor ? 1 : 0);
            int actual = Math.min(rounded, line.available());
            if (actual <= 0) {
                continue;
            }
            Money gross = line.effectivePrice().multiply(BigDecimal.valueOf(actual));
            Money commission = gross.multiply(EconomicRules.SALES_COMMISSION_RATE);
            Money net = gross.subtract(commission);
            WarehouseConsumption physical = state.warehouse().consumeAllocated(entry.getKey(), actual);
            if (physical.quantity() != actual) {
                throw new SimulationInvariantException("店铺库存与物理库存不一致: " + entry.getKey());
            }
            Money purchaseUnit =
                    new Money(
                            physical
                                    .purchaseCost()
                                    .amount()
                                    .divide(BigDecimal.valueOf(actual), Money.SCALE, RoundingMode.HALF_UP));
            store.removeInventory(entry.getKey(), actual);
            store.recordSale(entry.getKey(), actual, gross);
            state.recordSold(entry.getKey(), actual);
            state.fulfilmentStats().recordSold(actual);

            BigDecimal naturalRate = line.product().returnRate();
            double defectiveRate = Math.min(0.95, Math.max(0.40, naturalRate.doubleValue() * 2.0));
            double defectFraction = state.defectiveFraction(entry.getKey());
            BigDecimal afterDefect =
                    BigDecimal.valueOf(
                            naturalRate.doubleValue() * (1.0 - defectFraction) + defectiveRate * defectFraction);
            BigDecimal afterPrice =
                    BigDecimal.valueOf(
                            afterDefect.doubleValue()
                                    * context
                                    .demandModel()
                                    .returnPriceMultiplier(
                                            line.effectivePrice().amount().doubleValue(),
                                            line.product().referencePrice().doubleValue()));
            BigDecimal finalRate = afterPrice.min(new BigDecimal("0.95"));
            state
                    .pendingShipments()
                    .add(
                            new PendingShipment(
                                    state.nextShipmentId(),
                                    store.storeId(),
                                    entry.getKey(),
                                    actual,
                                    line.effectivePrice(),
                                    gross,
                                    net,
                                    commission,
                                    purchaseUnit,
                                    saleDate,
                                    saleDate.plusDays(EconomicRules.SHIP_DEADLINE_DAYS),
                                    finalRate,
                                    naturalRate,
                                    afterDefect,
                                    afterPrice,
                                    line.product().referencePrice()));
            sold += actual;
        }
        return sold;
    }

    private void applyCategoryCap(Map<String, DemandLine> raw, StoreTypeConfig type) {
        double capacity = EconomicRules.marketCapacity(type.storeTypeId());
        double fraction =
                Math.min(
                        1.0,
                        Math.max(
                                EconomicRules.CATEGORY_CAP_FRACTION.doubleValue(),
                                1.2 / Math.max(1, type.numCategories())));
        if (capacity <= 0 || fraction >= 1.0) {
            return;
        }
        Map<String, Double> categoryTotals = new LinkedHashMap<>();
        raw.values()
                .forEach(
                        line -> categoryTotals.merge(line.product().category(), line.demand(), Double::sum));
        double categoryCapacity = capacity * fraction;
        raw.replaceAll(
                (sku, line) -> {
                    double total = categoryTotals.getOrDefault(line.product().category(), 0.0);
                    double adjusted =
                            total <= 0
                                    ? line.demand()
                                    : line.demand() * categoryCapacity / (categoryCapacity + total);
                    return line.withDemand(adjusted);
                });
    }

    private DemandModel.PromotionBoost promotionBoost(
            DailyContext context, StoreState store, LocalDate saleDate) {
        if (store.promotionActive() == null) {
            return new DemandModel.PromotionBoost(1.0, 1.0);
        }
        return context.catalog().promotions().stream()
                .filter(p -> p.eventName().equals(store.promotionActive()))
                .filter(p -> isPromotionActive(p, saleDate))
                .findFirst()
                .map(
                        p ->
                                context
                                        .demandModel()
                                        .promotionBoost(
                                                p.maxDemandMultiplier(),
                                                p.elasticityBoost(),
                                                BigDecimal.valueOf(store.promotionDiscount())))
                .orElseGet(() -> new DemandModel.PromotionBoost(1.0, 1.0));
    }

    private double activeDiscount(StoreState store, DailyContext context, LocalDate saleDate) {
        if (store.promotionActive() == null) {
            return 0.0;
        }
        return context.catalog().promotions().stream()
                .filter(p -> p.eventName().equals(store.promotionActive()))
                .anyMatch(p -> isPromotionActive(p, saleDate))
                ? store.promotionDiscount()
                : 0.0;
    }

    private boolean isPromotionActive(PromotionConfig promotion, LocalDate date) {
        for (PromotionPeriod period : promotion.periods()) {
            LocalDate start = period.start().atYear(date.getYear());
            LocalDate end = period.end().atYear(date.getYear());
            if (end.isBefore(start)) {
                end = period.end().atYear(date.getYear() + 1);
            }
            if (!date.isBefore(start) && !date.isAfter(end)) {
                return true;
            }
        }
        return false;
    }

    private double eventDemandFactor(DailyContext context, String storeType, LocalDate date) {
        double factor = 1.0;
        for (MarketEvent event : context.catalog().events()) {
            LocalDate start = event.startDate().atYear(date.getYear());
            LocalDate end = start.plusDays(event.durationDays() - 1L);
            if (!date.isBefore(start) && !date.isAfter(end)) {
                factor *= event.demandEffects().getOrDefault(storeType, BigDecimal.ONE).doubleValue();
            }
        }
        return factor;
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private record DemandLine(
            Product product, CategoryParams params, int available, Money effectivePrice, double demand) {
        DemandLine withDemand(double value) {
            return new DemandLine(product, params, available, effectivePrice, value);
        }
    }
}
