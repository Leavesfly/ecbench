package io.github.ecommercebench.opponent.scam;

/**
 * 判断 Agent 文本是否明确同意支付 VIP 会员费。
 */
@FunctionalInterface
public interface VipConsentClassifier {
    boolean hasExplicitConsent(String customerMessage);
}
