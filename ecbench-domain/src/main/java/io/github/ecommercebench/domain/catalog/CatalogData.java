package io.github.ecommercebench.domain.catalog;

import java.util.List;
import java.util.Map;

/**
 * 仿真所需的只读业务目录聚合。
 *
 * @param products 商品定义列表，进入构造器时被冻结为不可变拷贝
 * @param suppliers 供应商列表，含正常与欺诈（bad）两类对手方
 * @param categoryParams 按类别索引的需求、退货与弹性参数
 * @param storeTypes 按店铺类型索引的开店费用、佣金与季节性配置
 * @param promotions 平台促销活动列表，一个活动可包含多个日期区间
 * @param events 会在年度仿真中影响需求或供应链的市场事件列表
 */
public record CatalogData(
    List<Product> products,
    List<Supplier> suppliers,
    Map<String, CategoryParams> categoryParams,
    Map<String, StoreTypeConfig> storeTypes,
    List<PromotionConfig> promotions,
    List<MarketEvent> events) {

  /** 紧凑构造器：将所有集合冻结为不可变拷贝，保证目录在仿真全程只读且线程安全。 */
  public CatalogData {
    products = List.copyOf(products);
    suppliers = List.copyOf(suppliers);
    categoryParams = Map.copyOf(categoryParams);
    storeTypes = Map.copyOf(storeTypes);
    promotions = List.copyOf(promotions);
    events = List.copyOf(events);
  }
}
