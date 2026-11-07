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
 *
 * <p>状态分四块：身份与成本（storeId/storeType/dailyRent 等开店即定，不可变）、库存与售价（inventory 与
 * prices 同步增删）、上一自然日的逐 SKU 销售明细（yesterdaySales，日切时由 SalesProcessor 重建）、声誉与累计口径
 * （reputation 参与每日需求计算，total* 为全程累计）。闭店只阻断面向 Agent 的写入口（publish/setPrice/
 * removeInventory/activatePromotion 会经 ensureOpen 抛 BusinessRuleException），后台的 record*、updateReputation
 * 等记账方法不校验营业状态，由调用方（日处理器）自行跳过已关闭店铺。
 */
public final class StoreState {

    /**
     * 店铺编号，形如 store_001，由 SimulationState.nextStoreId 分配。
     */
    private final String storeId;

    /**
     * 店铺类型 ID，对应 catalog 的 store_types（决定允许类目、市场容量与需求倍率）。
     */
    private final String storeType;

    /**
     * 店铺名称，Agent 开店时指定，仅用于展示。
     */
    private final String storeName;

    /**
     * 开店日期（重开店时取最近一次开店日）。
     */
    private final LocalDate openedDate;

    /**
     * 每日运营成本，开店时按店铺 tier 取定的 EconomicRules.operationsCost，由 OperationCostProcessor 每日扣一次。
     */
    private final Money dailyRent;

    /**
     * 已上架 SKU → 店铺内可售件数；与 prices 同步增删。
     */
    private final Map<String, Integer> inventory = new LinkedHashMap<>();

    /**
     * 已上架 SKU → 当前零售价；仅 publish/setPrice 可写。
     */
    private final Map<String, Money> prices = new LinkedHashMap<>();

    /**
     * 上一个自然日的逐 SKU 销售明细，由 SalesProcessor 每日重建，供 get_store_status 展示。
     */
    private final Map<String, DailySale> yesterdaySales = new LinkedHashMap<>();

    /**
     * 是否处于营业状态；关闭后 Agent 侧写入口会被 ensureOpen 拒绝。
     */
    private boolean open = true;

    /**
     * 累计售出件数（跨日不清零），驱动声誉基础值的 S 型曲线。
     */
    private int cumulativeSales;

    /**
     * 当前声誉，初始 0.5、限幅在 [0.15, 1.0]，直接参与每日需求计算。
     */
    private double reputation = 0.5;

    /**
     * 当前生效的促销活动名；无促销或已闭店为 null。
     */
    private String promotionActive;

    /**
     * 当前促销折扣率（如 0.2 表示按原价 80% 成交），无促销为 0。
     */
    private double promotionDiscount;

    /**
     * 累计成交额（零售价 × 件数的毛收入，平台佣金未从中扣除）。
     */
    private Money totalRevenue = Money.ZERO;

    /**
     * 累计已付运费（发货时逐笔累加）。
     */
    private Money totalShippingCost = Money.ZERO;

    /**
     * 累计退款额（退货处理时累加）。
     */
    private Money totalRefunds = Money.ZERO;

    /**
     * 近期退货量的指数衰减值，仅用于声誉惩罚，不是历史总量。
     */
    private double recentReturns;

    /**
     * 近期售出量的指数衰减值，作为声誉惩罚的分母。
     */
    private double recentSold;

    /**
     * 近期逾期取消量的指数衰减值，仅用于声誉惩罚。
     */
    private double recentCancellations;

    /**
     * 是否为重开店（同类型此前开过店），随 OpenStoreResult 回传给 Agent。
     */
    private boolean reopened;

    /**
     * 以开店即定的身份与成本字段建店；集合与累计量从空/零开始，营业状态默认为开。
     */
    public StoreState(
            String storeId, String storeType, String storeName, LocalDate openedDate, Money dailyRent) {
        this.storeId = Objects.requireNonNull(storeId, "storeId 不能为空");
        this.storeType = Objects.requireNonNull(storeType, "storeType 不能为空");
        this.storeName = Objects.requireNonNull(storeName, "storeName 不能为空");
        this.openedDate = Objects.requireNonNull(openedDate, "openedDate 不能为空");
        this.dailyRent = Objects.requireNonNull(dailyRent, "dailyRent 不能为空");
    }

    /**
     * 上架新商品或追加数量并设价；非正数量或已关店均拒绝。
     */
    public void publish(String sku, int quantity, Money price) {
        ensureOpen();
        if (quantity <= 0) {
            throw new BusinessRuleException("上架数量必须为正数");
        }
        inventory.merge(sku, quantity, Integer::sum);
        prices.put(sku, Objects.requireNonNull(price, "price 不能为空"));
    }

    /**
     * 仅允许修改已上架商品的售价（必须先 publish）。
     */
    public void setPrice(String sku, Money price) {
        ensureOpen();
        if (!inventory.containsKey(sku)) {
            throw new BusinessRuleException("商品尚未上架: " + sku);
        }
        prices.put(sku, Objects.requireNonNull(price, "price 不能为空"));
    }

    /**
     * 扣减店铺库存，扣到 0 时同时移价；仅当已上架且数量足够才成功。
     */
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

    /**
     * 一次性取走并清空全部库存与价格（闭店时使用），返回被排空的快照。
     */
    public Map<String, Integer> drainInventory() {
        Map<String, Integer> drained = Map.copyOf(inventory);
        inventory.clear();
        prices.clear();
        return drained;
    }

    /**
     * 闭店：置为非营业并清空当前促销；此后 Agent 侧的写入口会被 ensureOpen 拒绝，记账类方法不受限。
     */
    public void close() {
        open = false;
        promotionActive = null;
        promotionDiscount = 0.0;
    }

    /**
     * 激活或切换店铺促销：记录活动名与折扣率，需店铺营业；同一时刻只保留一个活动。
     */
    public void activatePromotion(String eventName, double discount) {
        ensureOpen();
        promotionActive = eventName;
        promotionDiscount = discount;
    }

    /**
     * 清空上一个自然日的销售明细；由 SalesProcessor 每日重算销量前调用，避免残留旧数据。
     */
    public void clearYesterdaySales() {
        yesterdaySales.clear();
    }

    /**
     * 记录一笔当日销售：累加累计销量与毛收入，并以 shipped=false 置入该 SKU 的销售明细。
     *
     * <p>明细是 put 而非 merge，同一 SKU 当日重复调用会覆盖前一条，调用方应保证每日每 SKU 只记一次。
     */
    public void recordSale(String sku, int quantity, Money grossRevenue) {
        cumulativeSales += quantity;
        recentSold += quantity;
        totalRevenue = totalRevenue.add(grossRevenue);
        yesterdaySales.put(
                sku, new DailySale(quantity, grossRevenue, false, Money.ZERO, 0, Money.ZERO));
    }

    /**
     * 记录一笔发货运费：累加店铺累计运费，并把运费并入该 SKU 的销售明细。
     *
     * <p>若该 SKU 已无明细（跨日后昨日明细被清空），只累加总额、不影响明细。
     */
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

    /**
     * 记录逾期取消件数：只累加近期取消量（用于声誉惩罚），不写销售明细。
     */
    public void recordCancellation(int quantity) {
        recentCancellations += quantity;
    }

    /**
     * 声誉限制在 [0.15, 1.0]，避免完全归零导致需求无限下降。
     */
    public void updateReputation(double value) {
        reputation = Math.max(0.15, Math.min(1.0, value));
    }

    /**
     * 每日将近期退货/售出/取消量乘以 0.85 做指数衰减，使旧事件对声誉的影响逐日淡出。
     */
    public void decayRecentServiceCounters() {
        recentReturns *= 0.85;
        recentSold *= 0.85;
        recentCancellations *= 0.85;
    }

    /**
     * 变更前校验营业状态：已闭店则抛 BusinessRuleException 阻断任何修改。
     */
    private void ensureOpen() {
        if (!open) {
            throw new BusinessRuleException("店铺已关闭: " + storeId);
        }
    }

    /**
     * 店铺编号。
     */
    public String storeId() {
        return storeId;
    }

    /**
     * 店铺类型 ID。
     */
    public String storeType() {
        return storeType;
    }

    /**
     * 店铺名称。
     */
    public String storeName() {
        return storeName;
    }

    /**
     * 开店日期。
     */
    public LocalDate openedDate() {
        return openedDate;
    }

    /**
     * 是否营业中；为 false 时 publish/setPrice/removeInventory/activatePromotion 会抛异常。
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * 每日运营成本（开店时按 tier 取定）。
     */
    public Money dailyRent() {
        return dailyRent;
    }

    /**
     * 店铺库存快照（不可变视图）。
     */
    public Map<String, Integer> inventory() {
        return Collections.unmodifiableMap(inventory);
    }

    /**
     * 售价快照（不可变视图）。
     */
    public Map<String, Money> prices() {
        return Collections.unmodifiableMap(prices);
    }

    /**
     * 上一自然日逐 SKU 销售明细快照（不可变视图），供 get_store_status 展示。
     */
    public Map<String, DailySale> yesterdaySales() {
        return Collections.unmodifiableMap(yesterdaySales);
    }

    /**
     * 累计售出件数（跨日不清零）。
     */
    public int cumulativeSales() {
        return cumulativeSales;
    }

    /**
     * 当前声誉，限幅 [0.15, 1.0]。
     */
    public double reputation() {
        return reputation;
    }

    /**
     * 当前促销活动名，无促销为 null。
     */
    public String promotionActive() {
        return promotionActive;
    }

    /**
     * 当前促销折扣率，无促销为 0。
     */
    public double promotionDiscount() {
        return promotionDiscount;
    }

    /**
     * 累计成交额（毛收入）。
     */
    public Money totalRevenue() {
        return totalRevenue;
    }

    /**
     * 累计已付运费。
     */
    public Money totalShippingCost() {
        return totalShippingCost;
    }

    /**
     * 累计退款额。
     */
    public Money totalRefunds() {
        return totalRefunds;
    }

    /**
     * 指数衰减后的近期退货量。
     */
    public double recentReturns() {
        return recentReturns;
    }

    /**
     * 指数衰减后的近期售出量。
     */
    public double recentSold() {
        return recentSold;
    }

    /**
     * 指数衰减后的近期逾期取消量。
     */
    public double recentCancellations() {
        return recentCancellations;
    }

    /**
     * 是否为重开店。
     */
    public boolean reopened() {
        return reopened;
    }

    /**
     * 标记为重开店：同类型此前已开过店时由 SimulationEngine.openStore 调用。
     */
    public void markReopened() {
        reopened = true;
    }

    /**
     * 单个店铺上一个自然日中某个 SKU 的销售明细；后续发货、退货会以返回新记录的方式更新。
     *
     * @param quantity     当日售出件数（当日只退货未售出时为 0）
     * @param revenue      当日成交毛收入
     * @param shipped      是否已归入发货运费
     * @param shippingCost 已归入的运费合计
     * @param returned     已到达的退货件数
     * @param refundAmount 已退还金额合计
     */
    public record DailySale(
            int quantity,
            Money revenue,
            boolean shipped,
            Money shippingCost,
            int returned,
            Money refundAmount) {
        /**
         * 返回并入本笔运费后的副本：置 shipped=true，并把 cost 累加到 shippingCost。
         */
        DailySale shipped(Money cost) {
            return new DailySale(quantity, revenue, true, shippingCost.add(cost), returned, refundAmount);
        }

        /**
         * 返回累加退货件数与退款额后的副本。
         */
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
