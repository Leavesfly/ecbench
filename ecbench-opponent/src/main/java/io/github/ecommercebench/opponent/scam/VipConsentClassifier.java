package io.github.ecommercebench.opponent.scam;

/**
 * 判断 Agent 文本是否明确同意支付 VIP 会员费。
 */
@FunctionalInterface
public interface VipConsentClassifier {
    /** 判断 Agent 的客户消息是否明确同意支付 VIP 会员费（用于门控欺诈话术）。 */
    boolean hasExplicitConsent(String customerMessage);
}
