package io.github.ecommercebench.simulation;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.catalog.PromotionConfig;
import io.github.ecommercebench.domain.catalog.PromotionPeriod;
import io.github.ecommercebench.domain.catalog.StoreTypeConfig;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.daily.BankruptcyProcessor;
import io.github.ecommercebench.simulation.daily.DailyContext;
import io.github.ecommercebench.simulation.daily.DailyProcessor;
import io.github.ecommercebench.simulation.daily.DailyResult;
import io.github.ecommercebench.simulation.daily.DeliveryProcessor;
import io.github.ecommercebench.simulation.daily.OperationCostProcessor;
import io.github.ecommercebench.simulation.daily.OverdueCancelProcessor;
import io.github.ecommercebench.simulation.daily.ReputationProcessor;
import io.github.ecommercebench.simulation.daily.ReturnProcessor;
import io.github.ecommercebench.simulation.daily.SalesProcessor;
import io.github.ecommercebench.simulation.daily.SettlementProcessor;
import io.github.ecommercebench.simulation.daily.StorageFeeProcessor;
import io.github.ecommercebench.simulation.demand.DemandModel;
import io.github.ecommercebench.simulation.dto.BalanceView;
import io.github.ecommercebench.simulation.dto.CloseStoreResult;
import io.github.ecommercebench.simulation.dto.ItemOperationResult;
import io.github.ecommercebench.simulation.dto.LiquidatedItem;
import io.github.ecommercebench.simulation.dto.OpenStoreResult;
import io.github.ecommercebench.simulation.dto.PriceItem;
import io.github.ecommercebench.simulation.dto.ProductView;
import io.github.ecommercebench.simulation.dto.PromotionJoinResult;
import io.github.ecommercebench.simulation.dto.PublishItem;
import io.github.ecommercebench.simulation.dto.PublishResult;
import io.github.ecommercebench.simulation.dto.QtyItem;
import io.github.ecommercebench.simulation.dto.ReturnResult;
import io.github.ecommercebench.simulation.dto.ReturnTrace;
import io.github.ecommercebench.simulation.dto.ReturnTraceRow;
import io.github.ecommercebench.simulation.dto.SetPricesResult;
import io.github.ecommercebench.simulation.dto.ShipResult;
import io.github.ecommercebench.simulation.dto.ShippedOrder;
import io.github.ecommercebench.simulation.dto.StoreStatus;
import io.github.ecommercebench.simulation.dto.StoreSummary;
import io.github.ecommercebench.simulation.dto.SupplierSource;
import io.github.ecommercebench.simulation.dto.SupplierView;
import io.github.ecommercebench.simulation.dto.WarehouseRow;
import io.github.ecommercebench.simulation.dto.WithdrawResult;
import io.github.ecommercebench.simulation.error.BusinessRuleException;
import io.github.ecommercebench.simulation.event.EventScheduler;
import io.github.ecommercebench.simulation.state.EscrowBatch;
import io.github.ecommercebench.simulation.state.PendingDelivery;
import io.github.ecommercebench.simulation.state.PendingReturn;
import io.github.ecommercebench.simulation.state.PendingShipment;
import io.github.ecommercebench.simulation.state.ShipSpeed;
import io.github.ecommercebench.simulation.state.SimulationState;
import io.github.ecommercebench.simulation.state.StoreState;
import io.github.ecommercebench.simulation.state.WarehouseConsumption;
import io.github.ecommercebench.simulation.state.WarehouseLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 电商仿真状态的唯一写入口。
 *
 * <p>工具层只能调用本类公开方法，不能直接修改内部聚合，从而保证库存、资金和统计同步更新。
 */
public final class SimulationEngine {

  private static final LocalDate START_DATE = LocalDate.of(2026, 1, 1);

  private final CatalogData catalog;
  private final RunConfig config;
  private final RandomStreams randomStreams;
  private final SimulationState state;
  private final Map<String, Product> productsById;
  private final DemandModel demandModel = new DemandModel();
  private final EventScheduler eventScheduler = new EventScheduler();
  private final List<DailyProcessor> dailyProcessors;
  private DailyResult lastDailyResult;

  public SimulationEngine(CatalogData catalog, RunConfig config, RandomStreams randomStreams) {
    this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
    this.config = Objects.requireNonNull(config, "config 不能为空");
    this.randomStreams = Objects.requireNonNull(randomStreams, "randomStreams 不能为空");
    this.state = SimulationState.initial(config.initialBalance(), START_DATE);
    this.productsById = new LinkedHashMap<>();
    catalog.products().forEach(product -> productsById.put(product.productId(), product));
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

  public OpenStoreResult openStore(String storeType, String storeName) {
    if (state.openStoreCount() >= state.maxStores()) {
      return OpenStoreResult.failure(
          "Maximum " + state.maxStores() + " stores allowed.", state.accounts().bank());
    }
    StoreTypeConfig type = catalog.storeTypes().get(storeType);
    if (type == null) {
      return OpenStoreResult.failure(
          "Invalid store type '" + storeType + "'.", state.accounts().bank());
    }
    boolean duplicate =
        state.stores().values().stream()
            .anyMatch(store -> store.isOpen() && store.storeType().equals(storeType));
    if (duplicate) {
      return OpenStoreResult.failure(
          "Already have an open " + storeType + " store.", state.accounts().bank());
    }
    if (state.accounts().bank().compareTo(type.setupFee()) < 0) {
      return OpenStoreResult.failure("Insufficient funds for setup fee.", state.accounts().bank());
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
          "Store '" + storeId + "' not found or already closed.");
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
                item.productId(), "quantity and retail_price must be positive."));
      } else if (product == null) {
        results.add(ItemOperationResult.failure(item.productId(), "Product not found."));
      } else if (!type.allowedCategories().contains(product.category())) {
        results.add(
            ItemOperationResult.failure(item.productId(), "Category not allowed in store."));
      } else if (state.warehouse().quantityOf(item.productId()) < item.quantity()) {
        results.add(ItemOperationResult.failure(item.productId(), "Insufficient warehouse stock."));
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

  public SetPricesResult setPrices(String storeId, List<PriceItem> prices) {
    StoreState store = requireOpenStore(storeId);
    List<ItemOperationResult> results = new ArrayList<>();
    for (PriceItem item : prices) {
      Money old = store.prices().get(item.productId());
      if (item.price() == null || item.price().compareTo(Money.ZERO) <= 0) {
        results.add(ItemOperationResult.failure(item.productId(), "price must be positive."));
      } else if (old == null) {
        results.add(ItemOperationResult.failure(item.productId(), "Product not in store."));
      } else {
        store.setPrice(item.productId(), item.price());
        results.add(ItemOperationResult.success(item.productId(), 0, old, item.price()));
      }
    }
    return new SetPricesResult(storeId, results);
  }

  public ReturnResult returnToWarehouse(String storeId, List<QtyItem> items) {
    StoreState store = requireOpenStore(storeId);
    List<ItemOperationResult> results = new ArrayList<>();
    for (QtyItem item : items) {
      int available = store.inventory().getOrDefault(item.productId(), 0);
      if (item.quantity() <= 0) {
        results.add(ItemOperationResult.failure(item.productId(), "quantity must be positive."));
      } else if (available < item.quantity()) {
        results.add(ItemOperationResult.failure(item.productId(), "Insufficient store stock."));
      } else {
        store.removeInventory(item.productId(), item.quantity());
        state.warehouse().releaseAllocation(item.productId(), item.quantity());
        results.add(ItemOperationResult.success(item.productId(), item.quantity(), null, null));
      }
    }
    return new ReturnResult(storeId, results);
  }

  public void receivePurchaseOrder(
      String productId, int quantity, Money unitPrice, boolean defective, int deliveryDelayDays) {
    receivePurchaseOrder("unknown", productId, quantity, unitPrice, defective, deliveryDelayDays);
  }

  public void receivePurchaseOrder(
      String supplierId,
      String productId,
      int quantity,
      Money unitPrice,
      boolean defective,
      int deliveryDelayDays) {
    if (!productsById.containsKey(productId)) {
      throw new BusinessRuleException("Product not found: " + productId);
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

  public BalanceView checkBalance() {
    Map<LocalDate, Money> upcoming = new LinkedHashMap<>();
    state.accounts().escrowBatches().stream()
        .sorted(
            Comparator.comparing(io.github.ecommercebench.simulation.state.EscrowBatch::settleDate))
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

  public List<StoreSummary> listStores() {
    return state.stores().values().stream().map(this::summary).toList();
  }

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

  private StoreState requireOpenStore(String storeId) {
    StoreState store = state.store(storeId);
    if (store == null || !store.isOpen()) {
      throw new BusinessRuleException("Store '" + storeId + "' not found or closed.");
    }
    return store;
  }

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
      return PromotionJoinResult.failure("Discount rate must be between 0.05 and 0.50.");
    }
    PromotionConfig promotion =
        catalog.promotions().stream()
            .filter(item -> item.eventName().equals(eventName))
            .findFirst()
            .orElse(null);
    if (promotion == null) {
      return PromotionJoinResult.failure("Event '" + eventName + "' not found.");
    }
    boolean active = isPromotionActive(promotion, state.currentDate());
    boolean upcoming = startsWithin(promotion, state.currentDate(), 30);
    if (!active && !upcoming) {
      return PromotionJoinResult.failure(
          "Promotion '" + eventName + "' is not active or starting within 30 days.");
    }
    store.activatePromotion(eventName, discountRate.doubleValue());
    return new PromotionJoinResult(
        true, storeId, eventName, discountRate, active, promotion.maxDemandMultiplier(), null);
  }

  public List<SupplierView> supplierSearch(String productName, String category, String storeType) {
    Set<String> productCategories =
        productName == null || productName.isBlank()
            ? Set.of()
            : catalog.products().stream()
                .filter(
                    product ->
                        product
                            .title()
                            .toLowerCase(java.util.Locale.ROOT)
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
                        || supplier.categoriesServed().stream().anyMatch(storeCategories::contains))
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
    for (int index = result.size() - 1; index > 0; index--) {
      int target = random.nextInt(index + 1);
      java.util.Collections.swap(result, index, target);
    }
    return List.copyOf(result);
  }

  public ReturnTrace traceReturnSources(String productId) {
    Set<String> productIds =
        productId == null || productId.isBlank()
            ? state.skuSupplierDelivered().keySet()
            : Set.of(productId);
    if (productId != null
        && !state.skuSupplierDelivered().containsKey(productId)
        && state.skuUnitsSold(productId) == 0) {
      return new ReturnTrace(
          List.of(), "No procurement/sales history for product '" + productId + "'.");
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
              ? "Realized return rate is far above this product's natural baseline — inspect "
                  + "the suppliers feeding this SKU's pool."
              : realized >= baseline * 1.5 && sold >= 10
                  ? "Realized return rate runs above baseline; worth watching the source suppliers."
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

  private double round3(double value) {
    return BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
  }

  public List<PendingShipment> listPendingShipments() {
    return List.copyOf(state.pendingShipments());
  }

  /** 发货时立即扣运费并把净收入放入托管，同时确定未来退货。 */
  public ShipResult shipOrders(List<Long> shipmentIds, ShipSpeed speed) {
    if (state.terminated()) {
      return ShipResult.failure(
          "Episode has ended; no further shipping.",
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
          "No matching pending shipments.",
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

  public DailyResult advanceToNextDay(LocalDate currentDay) {
    if (!state.currentDate().equals(currentDay)) {
      throw new BusinessRuleException(
          "current_day 必须等于 " + state.currentDate() + "，实际为 " + currentDay);
    }
    if (state.terminated()) {
      throw new BusinessRuleException("Episode has ended.");
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

  /** 最近一次日切的摘要；尚未推进任何一天时返回 null（供日志器读取当日仓储费等）。 */
  public DailyResult lastDailyResult() {
    return lastDailyResult;
  }

  public LocalDate currentDate() {
    return state.currentDate();
  }

  public SimulationState state() {
    return state;
  }

  public CatalogData catalog() {
    return catalog;
  }

  /** 按 SKU 查询商品定义；未知 SKU 返回 null。 */
  public Product product(String productId) {
    return productsById.get(productId);
  }

  public RunConfig config() {
    return config;
  }

  public RandomStreams randomStreams() {
    return randomStreams;
  }
}
