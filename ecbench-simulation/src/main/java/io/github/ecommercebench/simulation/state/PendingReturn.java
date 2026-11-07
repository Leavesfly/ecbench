package io.github.ecommercebench.simulation.state;

import io.github.ecommercebench.domain.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已确定会退货、等待返回仓库的商品批次（待退货队列中的一项）。
 *
 * <p>由 {@code SimulationEngine#shipOrders} 在发货时就按有效退货率逐件抽样生成（退货率已在发货时确定），
 * 到达日由 ReturnProcessor 处理：全额退款、商品重新入库、已付运费不返还。
 *
 * @param storeId           退货所属店铺，用于回写店铺级退货与退款统计
 * @param productId         退货商品 SKU
 * @param quantity          本批退货件数（抽样得到，可能小于发货件数）
 * @param refundPerUnit     每件退款额，取发货单的零售单价，按件数全额退
 * @param arrivalDate       退货到库日 = 发货日 + 3–7 天随机滑期
 * @param escrowBatchId     扣款来源的托管批次（发货时分配），退款优先从该批次冲抵，差额计入撤销佣金
 * @param shippingCostPerUnit 每件已付运费，退货时作为运费损失统计且不返还
 * @param purchaseUnitPrice 原采购单价，退货品重新入仓时作为新批次的成本单价
 * @param defectiveFraction 该 SKU 至今的缺陷占比，用于把退货拆成自然与缺陷两档归因
 */
public record PendingReturn(
        String storeId,
        String productId,
        int quantity,
        Money refundPerUnit,
        LocalDate arrivalDate,
        long escrowBatchId,
        Money shippingCostPerUnit,
        Money purchaseUnitPrice,
        BigDecimal defectiveFraction) {
}
