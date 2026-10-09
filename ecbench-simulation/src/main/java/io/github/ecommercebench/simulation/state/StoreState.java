package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.error.BusinessRuleException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单个店铺的可变状态聚合。所有库存和价格变更都经由显式方法完成。
 */
public final class StoreState {

    private final String storeId;
    private final String storeType;
    private final String storeName;
    private final LocalDate openedDate;
    private final Money dailyRent;
    private final Map<String, Integer> inventory = new LinkedHashMap<>();
    private final Map<String, Money> prices = new LinkedHashMap<>();
    private final Map<String, DailySale> yesterdaySales = new LinkedHashMap<>();
    private boolean open = true;
    private int cumulativeSales;
    private double reputation = 0.5;
    private String promotionActive;
    private double promotionDiscount;
    private Money totalRevenue = Money.ZERO;
    private Money totalShippingCost = Money.ZERO;
    private Money totalRefunds = Money.ZERO;
    private double recentReturns;
    private double recentSold;
    private double recentCancellations;
    private boolean reopened;

    public StoreState(
            String storeId, String storeType, String storeName, LocalDate openedDate, Money dailyRent) {
        this.storeId = Objects.requireNonNull(storeId, "storeId 不能为空");
        this.storeType = Objects.requireNonNull(storeType, "storeType 不能为空");
        this.storeName = Objects.requireNonNull(storeName, "storeName 不能为空");
        this.openedDate = Objects.requireNonNull(openedDate, "openedDate 不能为空");
        this.dailyRent = Objects.requireNonNull(dailyRent, "dailyRent 不能为空");
    }

    public void publish(String sku, int quantity, Money price) {
        ensureOpen();
        if (quantity <= 0) {
            throw new BusinessRuleException("上架数量必须为正数");
        }
        inventory.merge(sku, quantity, Integer::sum);
        prices.put(sku, Objects.requireNonNull(price, "price 不能为空"));
    }

    public void setPrice(String sku, Money price) {
        ensureOpen();
        if (!inventory.containsKey(sku)) {
            throw new BusinessRuleException("商品尚未上架: " + sku);
        }
        prices.put(sku, Objects.requireNonNull(price, "price 不能为空"));
    }

    public void removeInventory(String sku, int quantity) {
        ensureOpen();
        int available = inventory.getOrDefault(sku, 0);
        if (quantity <= 0 || quantity > available) {
            throw new BusinessRuleException("店铺库存不足: " + sku);
        }
        int remaining = available - quantity;
        if (remaining == 0) {
            inventory.remove(sku);
            prices.remove(sku);
        } else {
            inventory.put(sku, remaining);
        }
    }

    public Map<String, Integer> drainInventory() {
        Map<String, Integer> drained = Map.copyOf(inventory);
        inventory.clear();
        prices.clear();
        return drained;
    }

    public void close() {
        open = false;
        promotionActive = null;
        promotionDiscount = 0.0;
    }

    public void activatePromotion(String eventName, double discount) {
        ensureOpen();
        promotionActive = eventName;
        promotionDiscount = discount;
    }

    public void clearYesterdaySales() {
        yesterdaySales.clear();
    }

    public void recordSale(String sku, int quantity, Money grossRevenue) {
        cumulativeSales += quantity;
        recentSold += quantity;
        totalRevenue = totalRevenue.add(grossRevenue);
        yesterdaySales.put(
                sku, new DailySale(quantity, grossRevenue, false, Money.ZERO, 0, Money.ZERO));
    }

    public void recordShipment(String sku, Money shippingCost) {
        totalShippingCost = totalShippingCost.add(shippingCost);
        DailySale sale = yesterdaySales.get(sku);
        if (sale != null) {
            yesterdaySales.put(sku, sale.shipped(shippingCost));
        }
    }

    /**
     * 把到达的退货按 SKU 归因到当日销售明细，支撑 get_store_status 的逐商品退货展示。
     */
    public void recordReturn(String sku, int quantity, Money refund) {
        recentReturns += quantity;
        totalRefunds = totalRefunds.add(refund);
        DailySale sale = yesterdaySales.get(sku);
        if (sale != null) {
            yesterdaySales.put(sku, sale.withReturn(quantity, refund));
        } else {
            yesterdaySales.put(sku, new DailySale(0, Money.ZERO, false, Money.ZERO, quantity, refund));
        }
    }

    public void recordCancellation(int quantity) {
        recentCancellations += quantity;
    }

    public void updateReputation(double value) {
        reputation = Math.max(0.15, Math.min(1.0, value));
    }

    public void decayRecentServiceCounters() {
        recentReturns *= 0.85;
        recentSold *= 0.85;
        recentCancellations *= 0.85;
    }

    private void ensureOpen() {
        if (!open) {
            throw new BusinessRuleException("店铺已关闭: " + storeId);
        }
    }

    public String storeId() {
        return storeId;
    }

    public String storeType() {
        return storeType;
    }

    public String storeName() {
        return storeName;
    }

    public LocalDate openedDate() {
        return openedDate;
    }

    public boolean isOpen() {
        return open;
    }

    public Money dailyRent() {
        return dailyRent;
    }

    public Map<String, Integer> inventory() {
        return Collections.unmodifiableMap(inventory);
    }

    public Map<String, Money> prices() {
        return Collections.unmodifiableMap(prices);
    }

    public Map<String, DailySale> yesterdaySales() {
        return Collections.unmodifiableMap(yesterdaySales);
    }

    public int cumulativeSales() {
        return cumulativeSales;
    }

    public double reputation() {
        return reputation;
    }

    public String promotionActive() {
        return promotionActive;
    }

    public double promotionDiscount() {
        return promotionDiscount;
    }

    public Money totalRevenue() {
        return totalRevenue;
    }

    public Money totalShippingCost() {
        return totalShippingCost;
    }

    public Money totalRefunds() {
        return totalRefunds;
    }

    public double recentReturns() {
        return recentReturns;
    }

    public double recentSold() {
        return recentSold;
    }

    public double recentCancellations() {
        return recentCancellations;
    }

    public boolean reopened() {
        return reopened;
    }

    public void markReopened() {
        reopened = true;
    }

    public record DailySale(
            int quantity,
            Money revenue,
            boolean shipped,
            Money shippingCost,
            int returned,
            Money refundAmount) {
        DailySale shipped(Money cost) {
            return new DailySale(quantity, revenue, true, shippingCost.add(cost), returned, refundAmount);
        }

        DailySale withReturn(int returnedUnits, Money refund) {
            return new DailySale(
                    quantity,
                    revenue,
                    shipped,
                    shippingCost,
                    returned + returnedUnits,
                    refundAmount.add(refund));
        }
    }
}
