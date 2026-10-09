package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 一批同日入库且采购属性相同的库存。
 */
public final class WarehouseLot {

    private final String sku;
    private int quantity;
    private final LocalDate inboundDate;
    private final Money unitPrice;
    private final boolean defective;

    public WarehouseLot(
            String sku, int quantity, LocalDate inboundDate, Money unitPrice, boolean defective) {
        this.sku = Objects.requireNonNull(sku, "sku 不能为空");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须为正数");
        }
        this.quantity = quantity;
        this.inboundDate = Objects.requireNonNull(inboundDate, "inboundDate 不能为空");
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice 不能为空");
        this.defective = defective;
    }

    public String sku() {
        return sku;
    }

    public int quantity() {
        return quantity;
    }

    public LocalDate inboundDate() {
        return inboundDate;
    }

    public Money unitPrice() {
        return unitPrice;
    }

    public boolean defective() {
        return defective;
    }

    /**
     * 从本批次消耗至多 requested 件（不超过剩余量），扣减 quantity 并返回实际取走数；取空的批次由外层 FIFO 循环移除。仅供仓内消费使用。
     */
    int consume(int requested) {
        int taken = Math.min(requested, quantity);
        quantity -= taken;
        return taken;
    }
}
