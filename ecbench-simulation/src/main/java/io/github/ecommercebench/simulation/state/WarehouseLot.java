package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 一批同日入库且采购属性相同的库存。
 *
 * <p>批次是 FIFO 出库与仓储费计价的单位：同 SKU 的不同批次即使单价相同也不合并，新批次追加到队尾、出库从队首扣减，
 * 数量取尽的批次由 WarehouseInventory 从队列移除。生产代码共有四处入仓入口：到货（延迟为 0 时立即入仓）、
 * 延迟到货（DeliveryProcessor）、退货回库（ReturnProcessor）与逾期取消回库（OverdueCancelProcessor）。
 */
public final class WarehouseLot {

    /**
     * 批次所属商品 SKU，也是 WarehouseInventory 的分组键。
     */
    private final String sku;

    /**
     * 批次剩余件数；FIFO 出库时递减，降为 0 后该批次被移出队列。
     */
    private int quantity;

    /**
     * 入库日（实际到货或回库的那天），仓储费按 today - inboundDate 计库龄。
     */
    private final LocalDate inboundDate;

    /**
     * 本批采购单价，出库时乘以取走件数折算采购成本，供退货与结算口径使用。
     */
    private final Money unitPrice;

    /**
     * 是否劣质（供应商欺诈）批次；出库时据此累计缺陷件数，参与退货归因与退货率修正。
     */
    private final boolean defective;

    /**
     * 创建一批库存：入库时数量必须为正；SKU、入库日、单价不得为空。
     *
     * @param sku         商品 SKU
     * @param quantity    本批入库件数，必须为正
     * @param inboundDate 入库日，仓储费库龄的起算日
     * @param unitPrice   本批采购单价
     * @param defective   是否劣质批次（影响退货率与退货归因）
     */
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

    /**
     * 商品 SKU。
     */
    public String sku() {
        return sku;
    }

    /**
     * 批次当前剩余件数（随 FIFO 出库递减，不是入库时的原始数量）。
     */
    public int quantity() {
        return quantity;
    }

    /**
     * 入库日，仓储费库龄的起算日。
     */
    public LocalDate inboundDate() {
        return inboundDate;
    }

    /**
     * 本批采购单价，出库时用于折算采购成本。
     */
    public Money unitPrice() {
        return unitPrice;
    }

    /**
     * 是否为劣质批次；出库时据此累计缺陷件数。
     */
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
