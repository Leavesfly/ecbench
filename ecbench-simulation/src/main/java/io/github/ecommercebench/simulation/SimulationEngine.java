package io.github.ecommercebench.simulation;

import io.github.ecommercebench.domain.catalog.*;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.daily.*;
import io.github.ecommercebench.simulation.demand.DemandModel;
import io.github.ecommercebench.simulation.dto.*;
import io.github.ecommercebench.simulation.error.BusinessRuleException;
import io.github.ecommercebench.simulation.event.EventScheduler;
import io.github.ecommercebench.simulation.state.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 电商仿真状态的唯一写入口。
 *
 * <p>工具层只能调用本类公开方法，不能直接修改内部聚合，从而保证库存、资金和统计同步更新。
 */
public final class SimulationEngine {

    /**
     * 仿真固定起始日：所有 run 都从该日开始日切，checkBalance 的 day 序号也以它为原点。
     */
    private static final LocalDate START_DATE = LocalDate.of(2026, 1, 1);

    /**
     * 本次 run 的静态目录（商品、店铺类型、供应商、促销、事件等），构造后不再变化。
     */
    private final CatalogData catalog;

    /**
     * 运行配置（初始资金、最大天数等），构造后不再变化。
     */
    private final RunConfig config;

    /**
     * 按流名隔离的确定性随机源，保证同一 seed 下需求、退货等环节可复现。
     */
    private final RandomStreams randomStreams;

    /**
     * 全部可变业务状态的根对象。
     */
    private final SimulationState state;

    /**
     * 商品索引（productId → Product），避免各方法重复线性查找目录。
     */
    private final Map<String, Product> productsById;

    /**
     * 需求模型：价格弹性、促销增益、市场饱和等纯函数。
     */
    private final DemandModel demandModel = new DemandModel();

    /**
     * 市场事件调度器：按日期把事件通知追加到当日摘要。
     */
    private final EventScheduler eventScheduler = new EventScheduler();

    /**
     * 日切流水线，执行顺序在构造器中固定，运行期不可调整。
     */
    private final List<DailyProcessor> dailyProcessors;

    /**
     * 最近一次日切摘要；尚未推进任何一天时为 null。
     */
    private DailyResult lastDailyResult;

    /**
     * 装配一次 run：以初始资金建立状态根与商品索引，并固定日切流水线的执行顺序。
     */
    public SimulationEngine(CatalogData catalog, RunConfig config, RandomStreams randomStreams) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.config = Objects.requireNonNull(config, "config 不能为空");
        this.randomStreams = Objects.requireNonNull(randomStreams, "randomStreams 不能为空");
        this.state = SimulationState.initial(config.initialBalance(), START_DATE);
        this.productsById = new LinkedHashMap<>();
        catalog.products().forEach(product -> productsById.put(product.productId(), product));
        // 日级流水线的固定执行顺序：先扣成本与仓储费，再算销售，随后处理逾期/退货/结算/到货/声誉，最后判破产。
        this.dailyProcessors =
                List.of(
                        new OperationCostProcessor(),
                        new StorageFeeProcessor(),
                        new SalesProcessor(),
                        new OverdueCancelProcessor(),
                        new ReturnProcessor(),
                        new SettlementProcessor(),
                        new DeliveryProcessor(),
                        new ReputationProcessor(),
                        new BankruptcyProcessor());
    }

    /**
     * 开设一间新店：校验未超店铺上限、类型合法、同类型未重复开店且银行余额足以支付开店费后，
     * 扣除开店费并登记新店；任一条件不满足则返回带原因的失败结果且不扣款。
     */
    public OpenStoreResult openStore(String storeType, String storeName) {
        if (state.openStoreCount() >= state.maxStores()) {
            return OpenStoreResult.failure(
                    "最多只能开设 " + state.maxStores() + " 家店铺。", state.accounts().bank());
        }
        StoreTypeConfig type = catalog.storeTypes().get(storeType);
        if (type == null) {
            return OpenStoreResult.failure(
                    "无效的店铺类型 '" + storeType + "'。", state.accounts().bank());
        }
        boolean duplicate =
                state.stores().values().stream()
                        .anyMatch(store -> store.isOpen() && store.storeType().equals(storeType));
        if (duplicate) {
            return OpenStoreResult.failure(
                    "你已经有一家营业中的 " + storeType + " 店铺。", state.accounts().bank());
        }
        if (state.accounts().bank().compareTo(type.setupFee()) < 0) {
            return OpenStoreResult.failure("余额不足，无法支付开店费。", state.accounts().bank());
        }

        int previousOpenings = state.openCountForType(storeType);
        state.accounts().chargeBank(type.setupFee());
        StoreState store =
                new StoreState(
                        state.nextStoreId(),
                        storeType,
                        storeName,
                        state.currentDate(),
                        EconomicRules.operationsCost(type.tier()));
        if (previousOpenings > 0) {
            store.markReopened();
        }
        state.addStore(store);
        // 注意：每日实际扣费取自 EconomicRules.operationsCost(tier)（tier 1/2/3 = 130/100/60），而下面回填的
        // dailyOpsCost 用的是 catalog 的 daily_rent（现数据 tier 3 为 45），故与 market_search 展示的口径不一致。
        return new OpenStoreResult(
                true,
                store.storeId(),
                storeType,
                storeName,
                type.setupFee(),
                type.dailyRent(),
                store.reopened(),
                type.allowedCategories(),
                state.accounts().bank(),
                null);
    }

    /**
     * 关闭指定店铺。liquidate 为真时按残值回收率变现剩余库存并贷记银行；为假时仅释放仓库占用。 店铺不存在或已关闭时返回失败结果。
     */
    public CloseStoreResult closeStore(String storeId, boolean liquidate) {
        StoreState store = state.store(storeId);
        if (store == null || !store.isOpen()) {
            return new CloseStoreResult(
                    false,
                    storeId,
                    liquidate,
                    Map.of(),
                    Map.of(),
                    Money.ZERO,
                    state.accounts().bank(),
                    "未找到店铺 '" + storeId + "' 或该店铺已关闭。");
        }
        Map<String, Integer> remaining = store.drainInventory();
        Map<String, LiquidatedItem> liquidated = new LinkedHashMap<>();
        Money salvage = Money.ZERO;
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            if (liquidate) {
                WarehouseConsumption removed =
                        state.warehouse().consumeAllocated(entry.getKey(), entry.getValue());
                Money itemSalvage = removed.purchaseCost().multiply(EconomicRules.LIQUIDATION_SALVAGE_RATE);
                salvage = salvage.add(itemSalvage);
                liquidated.put(entry.getKey(), new LiquidatedItem(entry.getValue(), itemSalvage));
            } else {
                state.warehouse().releaseAllocation(entry.getKey(), entry.getValue());
            }
        }
        store.close();
        if (!salvage.isZero()) {
            state.accounts().creditBank(salvage);
        }
        return new CloseStoreResult(
                true,
                storeId,
                liquidate,
                liquidate ? Map.of() : remaining,
                liquidated,
                salvage,
                state.accounts().bank(),
                null);
    }

    /**
     * 按店铺类型与类目（均可空）过滤商品目录，返回可供选品的只读视图。
     */
    public List<ProductView> listProducts(String storeType, String category) {
        Predicate<Product> filter = product -> true;
        if (storeType != null && !storeType.isBlank()) {
            filter = filter.and(product -> storeType.equals(product.storeType()));
        }
        if (category != null && !category.isBlank()) {
            filter = filter.and(product -> category.equals(product.category()));
        }
        return catalog.products().stream()
                .filter(filter)
                .map(
                        product ->
                                new ProductView(
                                        product.productId(),
                                        product.title(),
                                        product.category(),
                                        product.brand(),
                                        product.storeType(),
                                        product.size(),
                                        product.referencePrice(),
                                        product.returnRate()))
                .toList();
    }

    /**
     * 将仓库现货上架到店铺：逐项校验数量为正、零售价合法、类目被店铺允许且仓库现货充足， 通过则占用仓库并在店铺发布；每项独立返回成功或失败原因。
     */
    public PublishResult publishToStore(String storeId, List<PublishItem> plan) {
        StoreState store = requireOpenStore(storeId);
        StoreTypeConfig type = catalog.storeTypes().get(store.storeType());
        List<ItemOperationResult> results = new ArrayList<>();
        for (PublishItem item : plan) {
            Product product = productsById.get(item.productId());
            if (item.quantity() <= 0
                    || item.retailPrice() == null
                    || item.retailPrice().compareTo(Money.ZERO) <= 0) {
                results.add(
                        ItemOperationResult.failure(
                                item.productId(), "quantity 与 retail_price 必须为正数。"));
            } else if (product == null) {
                results.add(ItemOperationResult.failure(item.productId(), "未找到该商品。"));
            } else if (!type.allowedCategories().contains(product.category())) {
                results.add(
                        ItemOperationResult.failure(item.productId(), "该品类不被此店铺允许。"));
            } else if (state.warehouse().quantityOf(item.productId()) < item.quantity()) {
                results.add(ItemOperationResult.failure(item.productId(), "仓库现货不足。"));
            } else {
                state.warehouse().allocate(item.productId(), item.quantity());
                store.publish(item.productId(), item.quantity(), item.retailPrice());
                results.add(
                        ItemOperationResult.success(
                                item.productId(), item.quantity(), null, item.retailPrice()));
            }
        }
        return new PublishResult(storeId, results);
    }

    /**
     * 批量修改已上架商品的零售价；非正价格或未在店铺发布的商品逐项返回失败，成功项记录新旧价。
     */
    public SetPricesResult setPrices(String storeId, List<PriceItem> prices) {
        StoreState store = requireOpenStore(storeId);
        List<ItemOperationResult> results = new ArrayList<>();
        for (PriceItem item : prices) {
            Money old = store.prices().get(item.productId());
            if (item.price() == null || item.price().compareTo(Money.ZERO) <= 0) {
                results.add(ItemOperationResult.failure(item.productId(), "价格必须为正数。"));
            } else if (old == null) {
                results.add(ItemOperationResult.failure(item.productId(), "该商品未在此店铺上架。"));
            } else {
                store.setPrice(item.productId(), item.price());
                results.add(ItemOperationResult.success(item.productId(), 0, old, item.price()));
            }
        }
        return new SetPricesResult(storeId, results);
    }

    /**
     * 将店铺内滞销库存退回仓库：校验店铺现货充足后扣减店铺库存并释放对应的仓库占用。
     */
    public ReturnResult returnToWarehouse(String storeId, List<QtyItem> items) {
        StoreState store = requireOpenStore(storeId);
        List<ItemOperationResult> results = new ArrayList<>();
        for (QtyItem item : items) {
            int available = store.inventory().getOrDefault(item.productId(), 0);
            if (item.quantity() <= 0) {
                results.add(ItemOperationResult.failure(item.productId(), "数量必须为正数。"));
            } else if (available < item.quantity()) {
                results.add(ItemOperationResult.failure(item.productId(), "店铺库存不足。"));
            } else {
                store.removeInventory(item.productId(), item.quantity());
                state.warehouse().releaseAllocation(item.productId(), item.quantity());
                results.add(ItemOperationResult.success(item.productId(), item.quantity(), null, null));
            }
        }
        return new ReturnResult(storeId, results);
    }

    /**
     * 不区分供应商的采购到货入口， supplierId 退化为 "unknown"。
     */
    public void receivePurchaseOrder(
            String productId, int quantity, Money unitPrice, boolean defective, int deliveryDelayDays) {
        receivePurchaseOrder("unknown", productId, quantity, unitPrice, defective, deliveryDelayDays);
    }

    /**
     * 录入一笔采购到货。deliveryDelayDays 为 0 时直接入仓并记录交付；大于 0 时挂为待到货批次， 由日级 DeliveryProcessor 在到达日入仓。defective 标记劣质批次（影响退货率）。
     */
    public void receivePurchaseOrder(
            String supplierId,
            String productId,
            int quantity,
            Money unitPrice,
            boolean defective,
            int deliveryDelayDays) {
        if (!productsById.containsKey(productId)) {
            throw new BusinessRuleException("未找到商品：" + productId);
        }
        if (quantity <= 0 || deliveryDelayDays < 0) {
            throw new BusinessRuleException("采购数量必须为正数且到货延迟不能为负数");
        }
        if (deliveryDelayDays == 0) {
            state
                    .warehouse()
                    .addLot(new WarehouseLot(productId, quantity, state.currentDate(), unitPrice, defective));
            state.recordDelivery(supplierId, productId, quantity, defective);
            return;
        }
        state
                .pendingDeliveries()
                .add(
                        new PendingDelivery(
                                state.nextDeliveryId(),
                                supplierId,
                                productId,
                                quantity,
                                unitPrice,
                                state.currentDate().plusDays(deliveryDelayDays),
                                defective));
    }

    /**
     * 盘点仓库：按 SKU 汇总已分配量与物理现货量，并回填商品标题、类目、尺寸与最新单价。
     */
    public List<WarehouseRow> checkWarehouse() {
        Map<String, WarehouseRow> rows = new LinkedHashMap<>();
        for (WarehouseLot lot : state.warehouse().allLots()) {
            Product product = productsById.get(lot.sku());
            rows.put(
                    lot.sku(),
                    new WarehouseRow(
                            lot.sku(),
                            state.warehouse().quantityOf(lot.sku()),
                            state.warehouse().physicalQuantityOf(lot.sku()),
                            product == null ? "" : product.title(),
                            product == null ? "" : product.category(),
                            lot.unitPrice(),
                            product == null ? "" : product.size()));
        }
        return List.copyOf(rows.values());
    }

    /**
     * 查看账户全貌：银行/钱包/待结算余额、未发货预期收入、总资产，以及按结算日聚合的托管入账时间表。
     */
    public BalanceView checkBalance() {
        Map<LocalDate, Money> upcoming = new LinkedHashMap<>();
        state.accounts().escrowBatches().stream()
                .sorted(
                        Comparator.comparing(EscrowBatch::settleDate))
                .forEach(batch -> upcoming.merge(batch.settleDate(), batch.amount(), Money::add));
        Money unshipped =
                state.pendingShipments().stream()
                        .map(PendingShipment::revenueNet)
                        .reduce(Money.ZERO, Money::add);
        int day = Math.toIntExact(ChronoUnit.DAYS.between(START_DATE, state.currentDate()));
        return new BalanceView(
                state.accounts().bank(),
                state.accounts().wallet(),
                state.accounts().pendingSettlement(),
                unshipped,
                state.totalAssets(),
                upcoming,
                day,
                state.currentDate());
    }

    /**
     * 从银行提现到钱包；余额不足等非法请求会被 accounts().withdraw 抛出的异常转为失败结果。
     */
    public WithdrawResult withdraw(Money requested) {
        try {
            Money withdrawn = state.accounts().withdraw(requested);
            return new WithdrawResult(
                    true, withdrawn, state.accounts().bank(), state.accounts().wallet(), null);
        } catch (IllegalArgumentException exception) {
            return WithdrawResult.failure(
                    exception.getMessage(), state.accounts().bank(), state.accounts().wallet());
        }
    }

    /**
     * 返回指定店铺的实时状态快照（商品、价格、累计营收/运费/退款）；店铺不存在或已关闭时抛业务异常。
     */
    public StoreStatus storeStatus(String storeId) {
        StoreState store = requireOpenStore(storeId);
        return new StoreStatus(
                summary(store),
                store.inventory(),
                store.prices(),
                store.totalRevenue(),
                store.totalShippingCost(),
                store.totalRefunds());
    }

    /**
     * 列出当前所有店铺（含已关闭）的概要信息。
     */
    public List<StoreSummary> listStores() {
        return state.stores().values().stream().map(this::summary).toList();
    }

    /**
     * 把店铺状态压缩成列表用的概要：含营业状态、声誉与店内件数，不含价格与库存明细。
     */
    private StoreSummary summary(StoreState store) {
        int units = store.inventory().values().stream().mapToInt(Integer::intValue).sum();
        return new StoreSummary(
                store.storeId(),
                store.storeType(),
                store.storeName(),
                store.isOpen(),
                store.reputation(),
                units);
    }

    /**
     * 取店铺并要求其处于营业状态，否则抛业务异常；供面向 Agent 的写入口复用。
     */
    private StoreState requireOpenStore(String storeId) {
        StoreState store = state.store(storeId);
        if (store == null || !store.isOpen()) {
            throw new BusinessRuleException("未找到店铺 '" + storeId + "' 或该店铺已关闭。");
        }
        return store;
    }

    /**
     * 参与促销活动：折扣率需落在 [0.05, 0.50]，活动必须处于进行中或 30 天内即将开始； 通过后在店铺上激活折扣并返回该活动的最大需求乘子。
     */
    public PromotionJoinResult joinPromotion(
            String storeId, String eventName, BigDecimal discountRate) {
        StoreState store;
        try {
            store = requireOpenStore(storeId);
        } catch (BusinessRuleException exception) {
            return PromotionJoinResult.failure(exception.getMessage());
        }
        if (discountRate.compareTo(new BigDecimal("0.05")) < 0
                || discountRate.compareTo(new BigDecimal("0.50")) > 0) {
            return PromotionJoinResult.failure("折扣率必须在 0.05 与 0.50 之间。");
        }
        PromotionConfig promotion =
                catalog.promotions().stream()
                        .filter(item -> item.eventName().equals(eventName))
                        .findFirst()
                        .orElse(null);
        if (promotion == null) {
            return PromotionJoinResult.failure("未找到促销活动 '" + eventName + "'。");
        }
        boolean active = isPromotionActive(promotion, state.currentDate());
        boolean upcoming = startsWithin(promotion, state.currentDate(), 30);
        if (!active && !upcoming) {
            return PromotionJoinResult.failure(
                    "促销 '" + eventName + "' 既未在进行中，也不在 30 天内开始。");
        }
        store.activatePromotion(eventName, discountRate.doubleValue());
        return new PromotionJoinResult(
                true, storeId, eventName, discountRate, active, promotion.maxDemandMultiplier(), null);
    }

    /**
     * 检索供应商：按类目、店铺类型允许类目、商品名关键字（通配到其类目）三重过滤，命中集经确定性洗乱后返回。
     */
    public List<SupplierView> supplierSearch(String productName, String category, String storeType) {
        Set<String> productCategories =
                productName == null || productName.isBlank()
                        ? Set.of()
                        : catalog.products().stream()
                        .filter(
                                product ->
                                        product
                                                .title()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(productName.toLowerCase(java.util.Locale.ROOT)))
                        .map(Product::category)
                        .collect(Collectors.toSet());
        Set<String> storeCategories =
                storeType == null || !catalog.storeTypes().containsKey(storeType)
                        ? Set.of()
                        : Set.copyOf(catalog.storeTypes().get(storeType).allowedCategories());
        List<SupplierView> result =
                catalog.suppliers().stream()
                        .filter(supplier -> category == null || supplier.categoriesServed().contains(category))
                        .filter(
                                supplier ->
                                        storeType == null
                                                || supplier.categoriesServed().stream()
                                                .anyMatch(storeCategories::contains))
                        .filter(
                                supplier ->
                                        productName == null
                                                || productName.isBlank()
                                                || supplier.categoriesServed().stream()
                                                .anyMatch(productCategories::contains))
                        .map(
                                supplier ->
                                        new SupplierView(
                                                supplier.supplierName(),
                                                supplier.supplierEmail(),
                                                supplier.categoriesServed()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var random =
                randomStreams.stream("supplier-search:" + productName + ":" + category + ":" + storeType);
        // 使用确定性随机流做 Fisher-Yates 洗乱，同一查询条件返回固定顺序但不同查询间充分打乱。
        for (int index = result.size() - 1; index > 0; index--) {
            int target = random.nextInt(index + 1);
            java.util.Collections.swap(result, index, target);
        }
        return List.copyOf(result);
    }

    /**
     * 退货溯源：按 SKU 统计各供应商到货占比，并将实测退货率与自然基准对比， 明显偏高时给出提醒文案，用于定位问题供应商。productId 为空时遍历全部有采购历史的 SKU。
     */
    public ReturnTrace traceReturnSources(String productId) {
        Set<String> productIds =
                productId == null || productId.isBlank()
                        ? state.skuSupplierDelivered().keySet()
                        : Set.of(productId);
        if (productId != null
                && !state.skuSupplierDelivered().containsKey(productId)
                && state.skuUnitsSold(productId) == 0) {
            return new ReturnTrace(
                    List.of(), "商品 '" + productId + "' 没有采购/销售历史。");
        }
        List<ReturnTraceRow> rows = new ArrayList<>();
        for (String sku : productIds) {
            Map<String, Integer> delivered = state.skuSupplierDelivered().getOrDefault(sku, Map.of());
            int total = delivered.values().stream().mapToInt(Integer::intValue).sum();
            List<SupplierSource> sources =
                    delivered.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .map(
                                    entry ->
                                            new SupplierSource(
                                                    entry.getKey(),
                                                    entry.getValue(),
                                                    total == 0 ? 0.0 : (double) entry.getValue() / total))
                            .toList();
            int sold = state.skuUnitsSold(sku);
            int returned = state.skuUnitsReturned(sku);
            Product product = productsById.get(sku);
            double baseline = product == null ? 0.05 : product.returnRate().doubleValue();
            double realized = sold == 0 ? 0.0 : (double) returned / sold;
            String note =
                    realized >= Math.max(0.25, baseline * 2) && sold >= 10
                            ? "实际退货率远高于该商品的天然基线——请检查为此 SKU 供货的供应商。"
                            : realized >= baseline * 1.5 && sold >= 10
                            ? "实际退货率高于基线，值得关注供货的供应商。"
                            : "";
            rows.add(
                    new ReturnTraceRow(
                            sku,
                            product == null ? "" : product.title(),
                            product == null ? "" : product.category(),
                            total,
                            sold,
                            returned,
                            round3(realized),
                            round3(baseline),
                            sources,
                            note));
        }
        rows.sort(Comparator.comparingDouble(ReturnTraceRow::realizedReturnRate).reversed());
        return new ReturnTrace(rows, null);
    }

    /**
     * 判断促销在给定日期是否处于活动区间；周期 end 早于 start 视为跨年，把 end 推到下一年。
     */
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

    /**
     * 判断促销在未来 days 天内是否“即将开始”（只算严格晚于 date 的区间）；供参与促销时的 30 天校验。
     */
    private boolean startsWithin(PromotionConfig promotion, LocalDate date, int days) {
        LocalDate limit = date.plusDays(days);
        for (PromotionPeriod period : promotion.periods()) {
            for (int year = date.getYear(); year <= limit.getYear(); year++) {
                LocalDate start = period.start().atYear(year);
                if (start.isAfter(date) && !start.isAfter(limit)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按 3 位小数四舍五入，用于退货率等展示值。
     */
    private double round3(double value) {
        return BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 返回待发货订单快照（不可变副本），供 Agent 查询可发货清单。
     */
    public List<PendingShipment> listPendingShipments() {
        return List.copyOf(state.pendingShipments());
    }

    /**
     * 发货时立即扣运费并把净收入放入托管，同时确定未来退货。
     */
    public ShipResult shipOrders(List<Long> shipmentIds, ShipSpeed speed) {
        if (state.terminated()) {
            return ShipResult.failure(
                    "Episode 已结束，无法继续发货。",
                    state.accounts().bank(),
                    state.accounts().pendingSettlement());
        }
        Set<Long> wanted = shipmentIds == null ? null : Set.copyOf(shipmentIds);
        List<PendingShipment> targets =
                state.pendingShipments().stream()
                        .filter(shipment -> wanted == null || wanted.contains(shipment.shipmentId()))
                        .toList();
        if (targets.isEmpty()) {
            return ShipResult.failure(
                    "没有符合条件的待发货订单。",
                    state.accounts().bank(),
                    state.accounts().pendingSettlement());
        }

        Money totalShipping = Money.ZERO;
        Money totalEscrow = Money.ZERO;
        List<ShippedOrder> shipped = new ArrayList<>();
        for (PendingShipment shipment : targets) {
            Product product = productsById.get(shipment.productId());
            EconomicRules.ShippingRule shippingRule = EconomicRules.shipping(speed);
            Money cost =
                    EconomicRules.sizeCost(product == null ? "Small" : product.size())
                            .shipping()
                            .multiply(shippingRule.costMultiplier())
                            .multiply(BigDecimal.valueOf(shipment.quantity()));
            state.accounts().chargeBank(cost);
            totalShipping = totalShipping.add(cost);
            StoreState store = state.store(shipment.storeId());
            if (store != null) {
                store.recordShipment(shipment.productId(), cost);
            }

            long batchId = state.nextBatchId();
            LocalDate settlesOn = state.currentDate().plusDays(EconomicRules.SETTLEMENT_WINDOW_DAYS);
            state
                    .accounts()
                    .addEscrow(
                            new EscrowBatch(batchId, shipment.revenueNet(), settlesOn, shipment.storeId()));
            totalEscrow = totalEscrow.add(shipment.revenueNet());

            double effectiveReturnRate =
                    Math.min(
                            0.95,
                            shipment.baseReturnRate().doubleValue()
                                    * shippingRule.returnMultiplier().doubleValue());
            var returnRandom =
                    randomStreams.stream("return:" + shipment.shipmentId() + ":" + speed.wireName());
            int returned = 0;
            for (int unit = 0; unit < shipment.quantity(); unit++) {
                if (returnRandom.nextDouble() < effectiveReturnRate) {
                    returned++;
                }
            }
            if (returned > 0) {
                int lagDays = 3 + returnRandom.nextInt(5);
                Money shippingPerUnit =
                        new Money(
                                cost.amount()
                                        .divide(
                                                BigDecimal.valueOf(shipment.quantity()),
                                                Money.SCALE,
                                                java.math.RoundingMode.HALF_UP));
                state
                        .pendingReturns()
                        .add(
                                new PendingReturn(
                                        shipment.storeId(),
                                        shipment.productId(),
                                        returned,
                                        shipment.unitPrice(),
                                        state.currentDate().plusDays(lagDays),
                                        batchId,
                                        shippingPerUnit,
                                        shipment.purchaseUnitPrice(),
                                        BigDecimal.valueOf(state.defectiveFraction(shipment.productId()))));
            }
            state.fulfilmentStats().recordShipped(speed);
            // 发货时记录期望退货分解（端口 Python ecommerce_env.py:943-962）：各层率×配送速度乘子并限幅 0.95。
            double speedMultiplier = shippingRule.returnMultiplier().doubleValue();
            double fBase =
                    Math.min(0.95, shipment.returnRateBeforeDefect().doubleValue() * speedMultiplier);
            double fDefect =
                    Math.min(0.95, shipment.returnRateAfterDefect().doubleValue() * speedMultiplier);
            double fPrice =
                    Math.min(0.95, shipment.returnRateAfterPrice().doubleValue() * speedMultiplier);
            int shippedQty = shipment.quantity();
            state
                    .returnStats()
                    .recordExpected(
                            shippedQty,
                            shippedQty * effectiveReturnRate,
                            shippedQty * fBase,
                            shippedQty * (fPrice - fDefect),
                            shippedQty * (effectiveReturnRate - fPrice),
                            shippedQty * Math.max(0.0, fDefect - fBase));
            shipped.add(
                    new ShippedOrder(
                            shipment.shipmentId(),
                            shipment.productId(),
                            shipment.quantity(),
                            cost,
                            shipment.revenueNet(),
                            settlesOn));
        }
        Set<Long> shippedIds =
                shipped.stream().map(ShippedOrder::shipmentId).collect(Collectors.toSet());
        state.pendingShipments().removeIf(item -> shippedIds.contains(item.shipmentId()));
        return new ShipResult(
                true,
                speed,
                shipped.size(),
                totalShipping,
                totalEscrow,
                state.accounts().bank(),
                state.accounts().pendingSettlement(),
                shipped,
                null);
    }

    /**
     * 推进到新的一天：先校验传入日期与当前状态一致且未终止，然后依次执行日级流水线各处理器，
     * 追加市场事件通知，并在达到 maxDays 时终止整个 episode。
     */
    public DailyResult advanceToNextDay(LocalDate currentDay) {
        if (!state.currentDate().equals(currentDay)) {
            throw new BusinessRuleException(
                    "current_day 必须等于 " + state.currentDate() + "，实际为 " + currentDay);
        }
        if (state.terminated()) {
            throw new BusinessRuleException("Episode 已结束。");
        }
        state.advanceDate();
        DailyResult result = new DailyResult(state.dayCount(), state.currentDate());
        DailyContext context =
                new DailyContext(
                        state.currentDate(), catalog, Map.copyOf(productsById), randomStreams, demandModel);
        for (DailyProcessor processor : dailyProcessors) {
            processor.process(state, context, result);
        }
        eventScheduler.appendNotifications(catalog, state.currentDate(), result);
        if (!state.terminated() && state.dayCount() >= config.maxDays()) {
            state.terminate("max_days");
        }
        this.lastDailyResult = result;
        return result;
    }

    /**
     * 最近一次日切的摘要；尚未推进任何一天时返回 null（供日志器读取当日仓储费等）。
     */
    public DailyResult lastDailyResult() {
        return lastDailyResult;
    }

    /**
     * 仿真当前业务日期（起始日 + 已推进天数）。
     */
    public LocalDate currentDate() {
        return state.currentDate();
    }

    /**
     * 返回可变状态根；供日志/统计与观测器读取，写操作仍应走本类方法。
     */
    public SimulationState state() {
        return state;
    }

    /**
     * 静态商品目录（只读），含商品/店铺类型/供应商/促销/事件。
     */
    public CatalogData catalog() {
        return catalog;
    }

    /**
     * 按 SKU 查询商品定义；未知 SKU 返回 null。
     */
    public Product product(String productId) {
        return productsById.get(productId);
    }

    /**
     * 运行配置（只读）。
     */
    public RunConfig config() {
        return config;
    }

    /**
     * 确定性随机源；调用方按流名取流以隔离并保证可复现。
     */
    public RandomStreams randomStreams() {
        return randomStreams;
    }
}
