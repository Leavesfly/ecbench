package io.github.ecommercebench.domain.catalog;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 商品类别的需求、退货率、弹性与采购成本参数。
 *
 * @param category 类别名称，作为目录中的索引键
 * @param storeType 该类别所属的店铺类型
 * @param defaultSize 新建商品时的默认规格
 * @param referencePriceMin 参考定价区间下限
 * @param referencePriceMax 参考定价区间上限
 * @param monthlySalesMin 月销量区间下限
 * @param monthlySalesMax 月销量区间上限
 * @param returnRateMin 退货率区间下限
 * @param returnRateMax 退货率区间上限
 * @param returnRateDescription 退货率的定性描述，供 Agent 提示使用
 * @param elasticityType 需求价格弹性模型类型，决定弹性如何作用于销量
 * @param elasticityParam 弹性模型的计算参数
 * @param wholesaleRatio 批发价相对参考价的系数，决定采购成本基准
 * @param costFloorRatio 成本价下限系数，低于此价的报价在谈判中不被接受
 * @param scamCapRatio 欺诈供应商可压到的价格上限系数
 */
public record CategoryParams(
    String category,
    String storeType,
    String defaultSize,
    BigDecimal referencePriceMin,
    BigDecimal referencePriceMax,
    int monthlySalesMin,
    int monthlySalesMax,
    BigDecimal returnRateMin,
    BigDecimal returnRateMax,
    String returnRateDescription,
    String elasticityType,
    BigDecimal elasticityParam,
    BigDecimal wholesaleRatio,
    BigDecimal costFloorRatio,
    BigDecimal scamCapRatio) {

  /** 紧凑构造器：锁定类别、店型与弹性类型等非空字段。 */
  public CategoryParams {
    Objects.requireNonNull(category, "category 不能为空");
    Objects.requireNonNull(storeType, "storeType 不能为空");
    Objects.requireNonNull(elasticityType, "elasticityType 不能为空");
  }
}
