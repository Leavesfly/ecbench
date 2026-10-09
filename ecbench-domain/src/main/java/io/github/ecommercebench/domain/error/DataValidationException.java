package io.github.ecommercebench.domain.error;

/** 数据文件缺失、字段不完整或字段值无法解析时抛出的异常。 */
public class DataValidationException extends RuntimeException {

  public DataValidationException(String message) {
    super(message);
  }

  public DataValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
