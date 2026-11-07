package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.stats.FraudStats;
import io.github.ecommercebench.simulation.stats.FulfilmentStats;
import io.github.ecommercebench.simulation.stats.ReturnStats;
import io.github.ecommercebench.simulation.stats.SupplierEngagement;

import java.time.LocalDate;
import java.util.*;

/**
 * 一次 benchmark run 的全部可变业务状态，不与其他 run 共享。
 *
 * <p>它是状态根：聚合账户、仓库、店铺、三类在途单据（待发货/待送达/待退货）、四组统计对象，
 * 以及日历、各类自增 ID 与终止标记。写入一律走 {@code record*} / {@code addStore} /
 * {@code advanceDate} / {@code terminate} 等入口，读出去引用的集合需注意是否可变。
 */
public final class SimulationState {

    /**
     * 单次 run 允许同时存在的店铺数上限，用于开店校验与看板展示。
     */
    private static final int MAX_STORES = 4;

    /**
     * 账户与资金流水（现金/托管/总资产）。
     */
    private final Accounts accounts;
    /**
     * 仓库库存与批次。
     */
    private final WarehouseInventory warehouse;
    /**
     * storeId → 店铺状态；关闭的店铺仍留在表中（重开次数另由 storeTypeOpenCount 记录）。
     */
    private final Map<String, StoreState> stores = new LinkedHashMap<>();
    /**
     * 店铺类型 → 累计开店次数（含重开），派生 store_reopens。
     */
    private final Map<String, Integer> storeTypeOpenCount = new LinkedHashMap<>();
    /**
     * 待发货订单（已成交未发出）。
     */
    private final List<PendingShipment> pendingShipments = new ArrayList<>();
    /**
     * 待送达采购（已发出未到货）。
     */
    private final List<PendingDelivery> pendingDeliveries = new ArrayList<>();
    /**
     * 待处理退货。
     */
    private final List<PendingReturn> pendingReturns = new ArrayList<>();
    // 以下 SKU 维度的计数表支撑退货溯源与缺陷占比等跨周期统计，仅随到货/售出/退货事件单调累加。
    private final Map<String, Integer> skuTotalDelivered = new LinkedHashMap<>();
    private final Map<String, Integer> skuDefectiveDelivered = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> skuSupplierDelivered = new LinkedHashMap<>();
    private final Map<String, Integer> skuUnitsSold = new LinkedHashMap<>();
    private final Map<String, Integer> skuUnitsReturned = new LinkedHashMap<>();
    private final FraudStats fraudStats = new FraudStats();
    private final FulfilmentStats fulfilmentStats = new FulfilmentStats();
    private final ReturnStats returnStats = new ReturnStats();
    private final SupplierEngagement supplierEngagement = new SupplierEngagement();
    /**
     * 仿真当前业务日期，每过一天加 1。
     */
    private LocalDate currentDate;
    /**
     * 已经过的天数（从 0 开始），用于周期判定与看板展示。
     */
    private int dayCount;
    /**
     * 各类实体的自增 ID 分配器；仅在当前 run 内单调递增。
     */
    private long nextStoreId = 1;
    private long nextOrderId = 1;
    private long nextBatchId = 1;
    private long nextShipmentId = 1;
    private long nextDeliveryId = 1;
    /**
     * 是否已终止（破产或到达最大天数）。
     */
    private boolean terminated;
    /**
     * 终止原因（bankrupt / max_days 等），未终止时为 null。
     */
    private String terminationReason;

    /**
     * 私有构造：账户以初始资金初始化，日历置为起始日，其余集合均为空。
     */
    private SimulationState(Money initialBalance, LocalDate startDate) {
        this.accounts = new Accounts(Objects.requireNonNull(initialBalance));
        this.warehouse = new WarehouseInventory();
        this.currentDate = Objects.requireNonNull(startDate);
    }

    /**
     * 工厂方法：以初始资金与起始日建立一份全新、独立于其他 run 的状态。
     */
    public static SimulationState initial(Money initialBalance, LocalDate startDate) {
        return new SimulationState(initialBalance, startDate);
    }

    /**
     * 分配形如 store_001 的递增店铺号。
     */
    public String nextStoreId() {
        return "store_%03d".formatted(nextStoreId++);
    }

    /**
     * 分配递增订单号（从 1 开始，全局唯一于当前 run）。
     */
    public long nextOrderId() {
        return nextOrderId++;
    }

    /**
     * 分配递增库存批号。
     */
    public long nextBatchId() {
        return nextBatchId++;
    }

    /**
     * 分配递增发货单号。
     */
    public long nextShipmentId() {
        return nextShipmentId++;
    }

    /**
     * 分配递增到货单号。
     */
    public long nextDeliveryId() {
        return nextDeliveryId++;
    }

    /**
     * 登记新店铺并按其类型累计开店次数（含重开），用于重复开店与 store_reopens 统计。
     */
    public void addStore(StoreState store) {
        stores.put(store.storeId(), store);
        storeTypeOpenCount.merge(store.storeType(), 1, Integer::sum);
    }

    /**
     * 返回某店铺类型的累计开店次数（不存在的类型返回 0）。
     */
    public int openCountForType(String storeType) {
        return storeTypeOpenCount.getOrDefault(storeType, 0);
    }

    /**
     * 各店铺类型的累计开店次数（含重开），用于计算 store_reopens = Σ max(0, count-1)。
     */
    public Map<String, Integer> storeTypeOpenCounts() {
        return Collections.unmodifiableMap(storeTypeOpenCount);
    }

    /**
     * 当前处于营业状态的店铺数（已关闭店铺不计入）。
     */
    public int openStoreCount() {
        return (int) stores.values().stream().filter(StoreState::isOpen).count();
    }

    /**
     * 返回店铺数上限常量，供开店校验与提示消息引用。
     */
    public int maxStores() {
        return MAX_STORES;
    }

    /**
     * 总资产 = 银行余额 + 平台钱包 + 待结算托管，由账户统一计算。
     */
    public Money totalAssets() {
        return accounts.totalAssets();
    }

    /**
     * 日历前进一天并累加天数计数（月底/年底交由 LocalDate 自理）。
     */
    public void advanceDate() {
        currentDate = currentDate.plusDays(1);
        dayCount++;
    }

    /**
     * 记录一笔到货：累加该 SKU 总量、按供应商溯源量，劣质批次另计入缺陷量。
     */
    public void recordDelivery(String supplierId, String sku, int quantity, boolean defective) {
        skuTotalDelivered.merge(sku, quantity, Integer::sum);
        skuSupplierDelivered
                .computeIfAbsent(sku, ignored -> new LinkedHashMap<>())
                .merge(supplierId, quantity, Integer::sum);
        if (defective) {
            skuDefectiveDelivered.merge(sku, quantity, Integer::sum);
        }
    }

    /**
     * 该 SKU 历史到货中的缺陷占比，用于把自然退货率修正为实际退货率；无到货记录时返回 0。
     */
    public double defectiveFraction(String sku) {
        int total = skuTotalDelivered.getOrDefault(sku, 0);
        return total == 0 ? 0.0 : (double) skuDefectiveDelivered.getOrDefault(sku, 0) / total;
    }

    /**
     * 累计某 SKU 的售出件数，用于退货率等分销统计。
     */
    public void recordSold(String sku, int quantity) {
        skuUnitsSold.merge(sku, quantity, Integer::sum);
    }

    /**
     * 累计某 SKU 的退货件数。
     */
    public void recordReturned(String sku, int quantity) {
        skuUnitsReturned.merge(sku, quantity, Integer::sum);
    }

    /**
     * 标记 episode 终止并记录原因（bankrupt / max_days 等），终止后不再发货或日切。
     */
    public void terminate(String reason) {
        terminated = true;
        terminationReason = reason;
    }

    public Accounts accounts() {
        return accounts;
    }

    public WarehouseInventory warehouse() {
        return warehouse;
    }

    /**
     * 店铺集合的不可变视图（含已关闭店铺，按创建顺序）。
     */
    public Map<String, StoreState> stores() {
        return Collections.unmodifiableMap(stores);
    }

    /**
     * 按 ID 查店铺；不存在时返回 null。
     */
    public StoreState store(String storeId) {
        return stores.get(storeId);
    }

    /**
     * 待发货列表（可变内部引用），日处理器会对其迭代并移除已处理项。
     */
    public List<PendingShipment> pendingShipments() {
        return pendingShipments;
    }

    /**
     * 待送达列表（可变内部引用），DeliveryProcessor 按日消费。
     */
    public List<PendingDelivery> pendingDeliveries() {
        return pendingDeliveries;
    }

    /**
     * 待退货列表（可变内部引用），ReturnProcessor 按日消费。
     */
    public List<PendingReturn> pendingReturns() {
        return pendingReturns;
    }

    public FraudStats fraudStats() {
        return fraudStats;
    }

    public FulfilmentStats fulfilmentStats() {
        return fulfilmentStats;
    }

    public ReturnStats returnStats() {
        return returnStats;
    }

    public SupplierEngagement supplierEngagement() {
        return supplierEngagement;
    }

    public LocalDate currentDate() {
        return currentDate;
    }

    public int dayCount() {
        return dayCount;
    }

    /**
     * SKU → (供应商 → 到货量) 溯源表；外层不可变，内层 Map 仍为内部可变实例。
     */
    public Map<String, Map<String, Integer>> skuSupplierDelivered() {
        return Collections.unmodifiableMap(skuSupplierDelivered);
    }

    public int skuUnitsSold(String sku) {
        return skuUnitsSold.getOrDefault(sku, 0);
    }

    public int skuUnitsReturned(String sku) {
        return skuUnitsReturned.getOrDefault(sku, 0);
    }

    public boolean terminated() {
        return terminated;
    }

    public String terminationReason() {
        return terminationReason;
    }
}
