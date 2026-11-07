package io.github.ecommercebench.simulation.dto;

import java.math.BigDecimal;

/**
 * 对 Agent 可见的商品目录条目，不含采购成本等隐藏供应商信息。
 *
 * <p>由 {@code SimulationEngine#listProducts} 按店铺类型与类目（均可空）过滤后生成，
 * {@code list_products} 工具逐项透出，其中标题在输出时截断至 80 字符。
 *
 * @param productId      商品唯一标识，后续采购与上架工具都靠它引用商品
 * @param title          商品标题
 * @param category       商品类目，需与店铺类型的 allowedCategories 匹配才能上架
 * @param brand          品牌名
 * @param storeType      所属店铺类型
 * @param size           商品规格（Small/Medium/Large），参与运费档位计算
 * @param referencePrice 参考定价，需求模型与定价决策的基准价
 * @param returnRate     商品天然退货率；当前 list_products 输出未包含此字段
 */
public record ProductView(
        String productId,
        String title,
        String category,
        String brand,
        String storeType,
        String size,
        BigDecimal referencePrice,
        BigDecimal returnRate) {
}
