package io.github.ecommercebench.simulation.dto;

import io.github.ecommercebench.domain.money.Money;

import java.util.Map;

/**
 * 单店运营与财务状态快照：概要 + 库存、售价与累计营收/运费/退款。
 *
 * <p>由 {@code SimulationEngine#storeStatus} 构造，仅对营业中的店铺可用（店铺不存在或已关闭会抛业务异常）。
 * 注意：目前 check_store_status 工具并未消费本记录（它直接读 StoreState 汇总昨日明细），
 * 该 DTO 保留为引擎侧的整店快照视图。
 *
 * @param summary      店铺概要：标识、店型、名称、营业状态、声誉与店内总件数
 * @param inventory    店铺库存快照：SKU → 店内可售件数（已从仓库划拨，不含仓库现货）
 * @param prices       当前售价快照：SKU → 零售价
 * @param totalRevenue 累计成交额（毛收入，跨日不清零）
 * @param shippingCost 累计已付运费
 * @param refunds      累计退款额
 */
public record StoreStatus(
        StoreSummary summary,
        Map<String, Integer> inventory,
        Map<String, Money> prices,
        Money totalRevenue,
        Money shippingCost,
        Money refunds) {
    /**
     * 紧凑构造器：把库存与售价拷贝为不可变 Map，避免调用方后续改动影响快照。
     */
    public StoreStatus {
        inventory = Map.copyOf(inventory);
        prices = Map.copyOf(prices);
    }
}
