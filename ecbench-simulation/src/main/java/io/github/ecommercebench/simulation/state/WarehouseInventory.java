package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按 SKU 保存 FIFO 库存批次，仓储费按每批实际库龄计算。 */
public final class WarehouseInventory {

  private final Map<String, Deque<WarehouseLot>> lotsBySku = new LinkedHashMap<>();
  private final Map<String, Integer> availableBySku = new LinkedHashMap<>();

  public void addLot(WarehouseLot lot) {
    Objects.requireNonNull(lot, "lot 不能为空");
    lotsBySku.computeIfAbsent(lot.sku(), ignored -> new ArrayDeque<>()).addLast(lot);
    availableBySku.merge(lot.sku(), lot.quantity(), Integer::sum);
  }

  /** 返回尚未分配到店铺的可用库存。 */
  public int quantityOf(String sku) {
    return availableBySku.getOrDefault(sku, 0);
  }

  /** 返回仍实际存放在仓库的数量，包含已上架但尚未售出的商品。 */
  public int physicalQuantityOf(String sku) {
    return lotsBySku.getOrDefault(sku, new ArrayDeque<>()).stream()
        .mapToInt(WarehouseLot::quantity)
        .sum();
  }

  public void allocate(String sku, int quantity) {
    int available = quantityOf(sku);
    if (quantity <= 0 || quantity > available) {
      throw new IllegalArgumentException("可用库存不足: " + sku);
    }
    availableBySku.put(sku, available - quantity);
  }

  public void releaseAllocation(String sku, int quantity) {
    if (quantity <= 0 || quantityOf(sku) + quantity > physicalQuantityOf(sku)) {
      throw new IllegalArgumentException("释放的分配库存无效: " + sku);
    }
    availableBySku.merge(sku, quantity, Integer::sum);
  }

  /** 已上架商品售出后只扣物理批次，不再扣可用库存。 */
  public WarehouseConsumption consumeAllocated(String sku, int quantity) {
    return consumePhysical(sku, quantity, false);
  }

  public List<WarehouseLot> lots(String sku) {
    return List.copyOf(lotsBySku.getOrDefault(sku, new ArrayDeque<>()));
  }

  /** 从最早入库批次开始扣减未分配库存和物理库存。 */
  public WarehouseConsumption consumeFifo(String sku, int requested) {
    return consumePhysical(sku, requested, true);
  }

  private WarehouseConsumption consumePhysical(String sku, int requested, boolean reduceAvailable) {
    if (requested <= 0) {
      throw new IllegalArgumentException("requested 必须为正数");
    }
    Deque<WarehouseLot> lots = lotsBySku.get(sku);
    if (lots == null) {
      return new WarehouseConsumption(0, 0, Money.ZERO);
    }
    int remaining = requested;
    int consumed = 0;
    int defective = 0;
    Money cost = Money.ZERO;
    while (remaining > 0 && !lots.isEmpty()) {
      WarehouseLot lot = lots.peekFirst();
      int taken = lot.consume(remaining);
      consumed += taken;
      remaining -= taken;
      if (lot.defective()) {
        defective += taken;
      }
      cost = cost.add(lot.unitPrice().multiply(BigDecimal.valueOf(taken)));
      if (lot.quantity() == 0) {
        lots.removeFirst();
      }
    }
    if (reduceAvailable) {
      availableBySku.put(sku, Math.max(0, quantityOf(sku) - consumed));
    }
    if (lots.isEmpty()) {
      lotsBySku.remove(sku);
      availableBySku.remove(sku);
    }
    return new WarehouseConsumption(consumed, defective, cost);
  }

  public Money calculateStorageFee(LocalDate today, StorageFeeRule rule) {
    Money total = Money.ZERO;
    for (Deque<WarehouseLot> lots : lotsBySku.values()) {
      for (WarehouseLot lot : lots) {
        int ageDays =
            Math.max(0, Math.toIntExact(ChronoUnit.DAYS.between(lot.inboundDate(), today)));
        total =
            total.add(rule.feePerItem(lot, ageDays).multiply(BigDecimal.valueOf(lot.quantity())));
      }
    }
    return total;
  }

  public List<WarehouseLot> allLots() {
    List<WarehouseLot> result = new ArrayList<>();
    lotsBySku.values().forEach(result::addAll);
    return List.copyOf(result);
  }
}
