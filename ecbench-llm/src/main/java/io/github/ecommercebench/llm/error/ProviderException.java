package io.github.ecommercebench.llm.error;

/** LLM Provider 调用失败；retryable 决定是否进入指数退避重试。 */
public class ProviderException extends RuntimeException {

  private final String provider;
  private final int statusCode;
  private final boolean retryable;

  public ProviderException(String provider, int statusCode, boolean retryable, String message) {
    super(message);
    this.provider = provider;
    this.statusCode = statusCode;
    this.retryable = retryable;
  }

  public ProviderException(String provider, boolean retryable, String message, Throwable cause) {
    super(message, cause);
    this.provider = provider;
    this.statusCode = 0;
    this.retryable = retryable;
  }

  public String provider() {
    return provider;
  }

  public int statusCode() {
    return statusCode;
  }

  public boolean retryable() {
    return retryable;
  }
}
