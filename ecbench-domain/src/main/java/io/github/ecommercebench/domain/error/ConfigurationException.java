package io.github.ecommercebench.domain.error;

/** 启动参数或模型注册表无效时抛出的异常。 */
public class ConfigurationException extends RuntimeException {

  /** 以错误描述构造配置异常。 */
  public ConfigurationException(String message) {
    super(message);
  }

  /** 以错误描述与原始 cause 构造配置异常，用于保留底层解析或 IO 失败信息。 */
  public ConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
