package io.github.ecommercebench.llm.error;

/**
 * LLM Provider 调用失败；retryable 决定是否进入指数退避重试。
 *
 * <p>为 RuntimeException，因此不污染 LlmClient.generate 的函数式签名；statusCode 为 0 表示非 HTTP 层失败（如网络/空响应）。
 */
public class ProviderException extends RuntimeException {

    private final String provider;
    private final int statusCode;
    private final boolean retryable;

    /** 来自已知 HTTP 状态码（如 429/5xx）的失败。 */
    public ProviderException(String provider, int statusCode, boolean retryable, String message) {
        super(message);
        this.provider = provider;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    /** 来自非 HTTP 层（网络/空响应/中断）的失败，statusCode 固定为 0。 */
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
