package io.github.ecommercebench.domain.error;

/** 数据文件缺失、字段不完整或字段值无法解析时抛出的异常。 */
public class DataValidationException extends RuntimeException {

  /** 以错误描述构造数据校验异常。 */
  public DataValidationException(String message) {
    super(message);
  }

  /** 以错误描述与原始 cause 构造数据校验异常，用于保留字段解析等底层失败信息。 */
  public DataValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
