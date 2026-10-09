package io.github.ecommercebench.opponent.scam;

import io.github.ecommercebench.opponent.model.FraudType;

/**
 * 提供各欺诈类型的语义和 VIP 前置门控判断。
 */
public final class ScamHandler {

    /** 是否需要先付 VIP 会员费（仅 VIP_FEE 类型）。 */
    public boolean requiresVipFee(FraudType type) {
        return type == FraudType.VIP_FEE;
    }

    /** 判断 SKU 是否为会员费条目本身。 */
    public boolean isMembershipFeeItem(String skuId) {
        return "MEMBERSHIP_FEE".equalsIgnoreCase(skuId);
    }

    /** 返回各欺诈类型的中文说明，供提示或诊断使用。 */
    public String instructions(FraudType type) {
        return switch (type) {
            case VIP_FEE -> "要求先支付一次性 VIP 会员费，但承诺的低价不会兑现。";
            case FUTURE_DISCOUNT -> "以未来订单折扣诱导当前成交，但后续不会兑现。";
            case QTY_BAIT -> "按完整数量收费，实际只交付约 60%–70%。";
            case QUALITY_DOWNGRADE -> "交付缺陷商品，导致客户退货率显著上升。";
            case FAKE_URGENCY -> "制造虚假紧迫感，并坚持抬高后的价格底线。";
            case NONE -> "无欺诈行为。";
        };
    }
}
