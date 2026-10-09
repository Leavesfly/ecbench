package io.github.ecommercebench.opponent.model;

/**
 * 供应商内核的结构化决策。
 */
public enum NegotiationDecision {
    OFFER,
    ACCEPT,
    REJECT,
    ERROR;

    /**
     * Python 契约中的首字母大写决策名（Offer/Accept/Reject/Error）。
     */
    public String wireName() {
        return switch (this) {
            case OFFER -> "Offer";
            case ACCEPT -> "Accept";
            case REJECT -> "Reject";
            case ERROR -> "Error";
        };
    }
}
