package io.github.ecommercebench.domain.error;

/**
 * 启动参数或模型注册表无效时抛出的异常。
 */
public class ConfigurationException extends RuntimeException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
