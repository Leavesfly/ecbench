package io.github.ecommercebench.simulation.daily;

import io.github.ecommercebench.domain.catalog.CatalogData;
import io.github.ecommercebench.domain.catalog.Product;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.simulation.demand.DemandModel;

import java.time.LocalDate;
import java.util.Map;

/**
 * 日切处理器共享的只读依赖。
 *
 * @param day 当前仿真自然日（销售按 day-1 的发生日回顾）
 * @param catalog 静态商品/店铺/促销/事件目录
 * @param products 按 SKU 索引的商品定义（由引擎预先构建）
 * @param randomStreams 按用途派生确定性随机流的根
 * @param demandModel 价格弹性/饱和/退货等纯计算模型
 */
public record DailyContext(
        LocalDate day,
        CatalogData catalog,
        Map<String, Product> products,
        RandomStreams randomStreams,
        DemandModel demandModel) {
}
