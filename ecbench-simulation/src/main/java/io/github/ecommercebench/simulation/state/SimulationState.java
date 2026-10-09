package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.simulation.stats.FraudStats;
import io.github.ecommercebench.simulation.stats.FulfilmentStats;
import io.github.ecommercebench.simulation.stats.ReturnStats;
import io.github.ecommercebench.simulation.stats.SupplierEngagement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次 benchmark run 的全部可变业务状态，不与其他 run 共享。
 */
public final class SimulationState {

    private static final int MAX_STORES = 4;

    private final Accounts accounts;
    private final WarehouseInventory warehouse;
    private final Map<String, StoreState> stores = new LinkedHashMap<>();
    private final Map<String, Integer> storeTypeOpenCount = new LinkedHashMap<>();
    private final List<PendingShipment> pendingShipments = new ArrayList<>();
    private final List<PendingDelivery> pendingDeliveries = new ArrayList<>();
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
    private LocalDate currentDate;
    private int dayCount;
    private long nextStoreId = 1;
    private long nextOrderId = 1;
    private long nextBatchId = 1;
    private long nextShipmentId = 1;
    private long nextDeliveryId = 1;
    private boolean terminated;
    private String terminationReason;

    private SimulationState(Money initialBalance, LocalDate startDate) {
        this.accounts = new Accounts(Objects.requireNonNull(initialBalance));
        this.warehouse = new WarehouseInventory();
        this.currentDate = Objects.requireNonNull(startDate);
    }

    /** 工厂方法：以初始资金与起始日建立一份全新、独立于其他 run 的状态。 */
    public static SimulationState initial(Money initialBalance, LocalDate startDate) {
        return new SimulationState(initialBalance, startDate);
    }

    /** 分配形如 store_001 的递增店铺号。 */
    public String nextStoreId() {
        return "store_%03d".formatted(nextStoreId++);
    }

    public long nextOrderId() {
        return nextOrderId++;
    }

    public long nextBatchId() {
        return nextBatchId++;
    }

    public long nextShipmentId() {
        return nextShipmentId++;
    }

    public long nextDeliveryId() {
        return nextDeliveryId++;
    }

    /** 登记新店铺并按其类型累计开店次数（含重开），用于重复开店与 store_reopens 统计。 */
    public void addStore(StoreState store) {
        stores.put(store.storeId(), store);
        storeTypeOpenCount.merge(store.storeType(), 1, Integer::sum);
    }

    public int openCountForType(String storeType) {
        return storeTypeOpenCount.getOrDefault(storeType, 0);
    }

    /**
     * 各店铺类型的累计开店次数（含重开），用于计算 store_reopens = Σ max(0, count-1)。
     */
    public Map<String, Integer> storeTypeOpenCounts() {
        return Collections.unmodifiableMap(storeTypeOpenCount);
    }

    public int openStoreCount() {
        return (int) stores.values().stream().filter(StoreState::isOpen).count();
    }

    public int maxStores() {
        return MAX_STORES;
    }

    public Money totalAssets() {
        return accounts.totalAssets();
    }

    public void advanceDate() {
        currentDate = currentDate.plusDays(1);
        dayCount++;
    }

    /** 记录一笔到货：累加该 SKU 总量、按供应商溯源量，劣质批次另计入缺陷量。 */
    public void recordDelivery(String supplierId, String sku, int quantity, boolean defective) {
        skuTotalDelivered.merge(sku, quantity, Integer::sum);
        skuSupplierDelivered
                .computeIfAbsent(sku, ignored -> new LinkedHashMap<>())
                .merge(supplierId, quantity, Integer::sum);
        if (defective) {
            skuDefectiveDelivered.merge(sku, quantity, Integer::sum);
        }
    }

    /** 该 SKU 历史到货中的缺陷占比，用于把自然退货率修正为实际退货率；无到货记录时返回 0。 */
    public double defectiveFraction(String sku) {
        int total = skuTotalDelivered.getOrDefault(sku, 0);
        return total == 0 ? 0.0 : (double) skuDefectiveDelivered.getOrDefault(sku, 0) / total;
    }

    public void recordSold(String sku, int quantity) {
        skuUnitsSold.merge(sku, quantity, Integer::sum);
    }

    public void recordReturned(String sku, int quantity) {
        skuUnitsReturned.merge(sku, quantity, Integer::sum);
    }

    /** 标记 episode 终止并记录原因（bankrupt / max_days 等），终止后不再发货或日切。 */
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

    public Map<String, StoreState> stores() {
        return Collections.unmodifiableMap(stores);
    }

    public StoreState store(String storeId) {
        return stores.get(storeId);
    }

    public List<PendingShipment> pendingShipments() {
        return pendingShipments;
    }

    public List<PendingDelivery> pendingDeliveries() {
        return pendingDeliveries;
    }

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
