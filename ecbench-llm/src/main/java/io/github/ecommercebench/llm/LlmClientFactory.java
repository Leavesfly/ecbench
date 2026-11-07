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
 * 根据模型配置创建对应协议客户端，是 LLM 层对外唯一的客户端装配入口。
 *
 * <p>工厂本身不持有连接，只持有解析器与 HTTP/重试基础设施；每次 {@link #create} 都克隆一份
 * {@code RestClient.Builder}，让不同 provider 的 baseUrl、鉴权头等连接配置彼此隔离。
 */
public final class LlmClientFactory {

    /** provider 与 baseUrl/apiKey/apiStyle 的解析器，负责补全默认端点与凭据。 */
    private final ProviderResolver resolver;
    /** 请求/响应 JSON 序列化器，直接透传给各客户端。 */
    private final ObjectMapper mapper;
    /** HTTP 客户端模板，创建客户端时克隆使用以隔离各 provider 的连接配置。 */
    private final RestClient.Builder restClientBuilder;
    /** 带退避重试的执行器，处理限流与瞬时网络故障。 */
    private final RetryExecutor retryExecutor;
    /** 重试策略（次数与退避曲线），与重试执行器配套使用。 */
    private final RetryPolicy retryPolicy;

    /** 注入解析器、序列化器、HTTP 模板与重试设施，它们被全部协议客户端共用。 */
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
     * <p>优先级：anthropic 协议 > openai responses 端点 > openai-compatible/google/openrouter
     * 兼容端点，其余回退到标准 OpenAI chat。每次克隆一个 RestClient.Builder 以隔离连接配置。
     *
     * @param config 模型配置，需能解析出 provider、baseUrl 与 apiKey（否则解析器抛配置异常）
     * @return 对应协议风格的客户端实例，调用方无需感知具体实现
     */
    public LlmClient create(ModelConfig config) {
        ResolvedProvider provider = resolver.resolve(config);
        if ("anthropic".equals(provider.apiStyle())) {
            // Anthropic Messages API 的路径与报文结构均独立于 OpenAI 协议，需专用客户端
            return new AnthropicClient(
                    provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
        }
        if ("responses".equals(provider.apiStyle())) {
            // OpenAI Responses 端点（/v1/responses）：报文结构与 chat/completions 不同
            return new OpenAiResponsesClient(
                    provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
        }
        if ("openai-compatible".equals(provider.name())
                || "google".equals(provider.name())
                || "openrouter".equals(provider.name())) {
            // 三方兼容端点：统一走 OpenAI 协议，可自带 baseUrl（google 的兼容网关也在此列）
            return new OpenAiCompatibleClient(
                    provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
        }
        // 其余（openai 及未特别标注的 provider）使用标准 chat/completions
        return new OpenAiChatClient(
                provider, mapper, restClientBuilder.clone(), retryExecutor, retryPolicy);
    }
}
