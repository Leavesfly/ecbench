package io.github.ecommercebench.simulation.error;

/**
 * 用户操作违反业务规则时抛出，由工具层转换为可恢复的 JSON 错误。
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
