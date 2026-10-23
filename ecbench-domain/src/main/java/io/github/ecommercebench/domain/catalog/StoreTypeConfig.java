package io.github.ecommercebench.domain.catalog;

import io.github.ecommercebench.domain.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 店铺类型及其开店、运营、佣金、可售类别和季节性配置。
 *
 * @param storeTypeId 店铺类型唯一标识，作为目录索引键
 * @param storeTypeName 展示名称
 * @param tier 难度层级，关联市场指引中的利润潜力文案
 * @param setupFee 开店一次性费用
 * @param dailyRent 每日固定房租
 * @param salesCommissionRate 平台销售佣金率
 * @param allowedCategories 允许经营的类别白名单，构造时冻结为不可变拷贝
 * @param numCategories 允许同时经营的类别数量上限
 * @param seasonality 十二个月份的需求季节系数，必须恰好 12 个
 */
public record StoreTypeConfig(
    String storeTypeId,
    String storeTypeName,
    int tier,
    Money setupFee,
    Money dailyRent,
    BigDecimal salesCommissionRate,
    List<String> allowedCategories,
    int numCategories,
    List<BigDecimal> seasonality) {

  /** 紧凑构造器：锁定标识、冻结列表，并校验季节性系数必须为 12 个月。 */
  public StoreTypeConfig {
    Objects.requireNonNull(storeTypeId, "storeTypeId 不能为空");
    allowedCategories = List.copyOf(allowedCategories);
    seasonality = List.copyOf(seasonality);
    if (seasonality.size() != 12) {
      throw new IllegalArgumentException("seasonality 必须包含 12 个月份系数");
    }
  }
}
