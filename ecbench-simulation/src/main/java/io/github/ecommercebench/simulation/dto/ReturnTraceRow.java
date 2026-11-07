package io.github.ecommercebench.simulation.dto;

import java.util.List;

/**
 * 单个 SKU 的退货溯源行：历史到货的供应商构成，以及实测退货率与天然基线的对比。
 *
 * <p>由 {@code SimulationEngine#traceReturnSources} 汇总生成，{@code trace_return_sources} 工具逐字段透出。
 * 口径要点：{@code realizedReturnRate} = 退货件数 / 售出件数，{@code naturalBaselineReturnRate} 取自商品目录；
 * 两个比率均按 3 位小数四舍五入，占比不做舍入。
 *
 * @param productId                 SKU 编号（商品目录主键）
 * @param title                     商品标题；目录中查不到该 SKU 时为空串
 * @param category                  商品类目；目录中查不到该 SKU 时为空串
 * @param totalUnitsDelivered       该 SKU 历史到货件数合计（各供应商之和）
 * @param unitsSold                 该 SKU 累计售出件数
 * @param unitsReturned             该 SKU 累计退货件数（自然退货与缺陷退货合计，含已回库的批次）
 * @param realizedReturnRate        已实现退货率 = unitsReturned / unitsSold；尚未售出时为 0
 * @param naturalBaselineReturnRate 商品目录登记的天然退货率；查不到该商品时回退为 0.05
 * @param supplierSources           各供应商的到货量与占比，按到货量降序；不可变列表
 * @param note                      退货率异常时的中文提醒文案，正常时为空串
 */
public record ReturnTraceRow(
        String productId,
        String title,
        String category,
        int totalUnitsDelivered,
        int unitsSold,
        int unitsReturned,
        double realizedReturnRate,
        double naturalBaselineReturnRate,
        List<SupplierSource> supplierSources,
        String note) {

    /**
     * 紧凑构造器：拷贝供应商来源列表，避免调用方后续改动外部集合影响本快照。
     */
    public ReturnTraceRow {
        supplierSources = List.copyOf(supplierSources);
    }
}
