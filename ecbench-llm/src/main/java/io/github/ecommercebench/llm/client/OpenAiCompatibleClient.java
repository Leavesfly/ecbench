package io.github.ecommercebench.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.provider.ResolvedProvider;
import org.springframework.web.client.RestClient;

/**
 * OpenAI wire-format 兼容服务客户端。
 *
 * <p>完全复用 {@link OpenAiChatClient} 的 /chat/completions 请求与响应解析，适用于 Qwen/DeepSeek/Kimi 等 OpenAI 兼容网关。
 */
public final class OpenAiCompatibleClient extends OpenAiChatClient {
    public OpenAiCompatibleClient(
            ResolvedProvider provider,
            ObjectMapper mapper,
            RestClient.Builder builder,
            RetryExecutor retryExecutor,
            RetryPolicy retryPolicy) {
        super(provider, mapper, builder, retryExecutor, retryPolicy);
    }
}
