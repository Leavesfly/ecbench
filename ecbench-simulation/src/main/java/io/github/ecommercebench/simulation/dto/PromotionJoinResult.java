package io.github.ecommercebench.simulation.dto;

import java.math.BigDecimal;

/** 店铺参加平台促销的结果。 */
public record PromotionJoinResult(
    boolean success,
    String storeId,
    String eventName,
    BigDecimal discountRate,
    boolean activeNow,
    BigDecimal maxDemandMultiplier,
    String error) {

  public static PromotionJoinResult failure(String error) {
    return new PromotionJoinResult(false, null, null, null, false, null, error);
  }
}
