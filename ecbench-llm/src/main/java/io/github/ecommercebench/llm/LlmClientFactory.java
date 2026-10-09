package io.github.ecommercebench.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.llm.client.AnthropicClient;
import io.github.ecommercebench.llm.client.OpenAiChatClient;
import io.github.ecommercebench.llm.client.OpenAiCompatibleClient;
import io.github.ecommercebench.llm.client.OpenAiResponsesClient;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.provider.ProviderResolver;
import io.github.ecommercebench.llm.provider.ResolvedProvider;
import org.springframework.web.client.RestClient;

/**
 * 根据模型配置创建对应协议客户端。
 */
public final class LlmClientFactory {

    private final ProviderResolver resolver;
    private final ObjectMapper mapper;
    private final RestClient.Builder restClientBuilder;
    private final RetryExecutor retryExecutor;
    private final RetryPolicy retryPolicy;

    public LlmClientFactory(
            ProviderResolver resolver,
            ObjectMapper mapper,
            RestClient.Builder restClientBuilder,
            RetryExecutor retryExecutor,
            RetryPolicy retryPolicy) {
        this.resolver = resolver;
        this.mapper = mapper;
        this.restClientBuilder = restClientBuilder;
        this.retryExecutor = retryExecutor;
        this.retryPolicy = retryPolicy;
    }

    /**
     * 根据已解析的 apiStyle 与 provider 名选择具体协议客户端。
     *
     * <p>优先级：anthropic 协议 > openai responses 端点 > openai-compatible/google/openrouter 兼容端点，
     * 其余回退到标准 OpenAI chat。每次克隆一个 RestClient.Builder 以隔离连接配置。
     */
    public LlmClient create(ModelConfig config) {
        ResolvedProvider provider = resolver.resolve(config);
        if ("anthropic".equals(provider.apiStyle())) {
            return new AnthropicClient(
                    provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
        }
        if ("responses".equals(provider.apiStyle())) {
            return new OpenAiResponsesClient(
                    provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
        }
        if ("openai-compatible".equals(provider.name())
                || "google".equals(provider.name())
                || "openrouter".equals(provider.name())) {
            return new OpenAiCompatibleClient(
                    provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
        }
        return new OpenAiChatClient(
                provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
    }
}
