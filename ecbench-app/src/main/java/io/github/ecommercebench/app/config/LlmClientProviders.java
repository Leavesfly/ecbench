package io.github.ecommercebench.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.llm.LlmClientFactory;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.provider.ProviderResolver;

import java.util.Map;

import org.springframework.web.client.RestClient;

/**
 * {@link LlmClientProvider} 的唯一构造点。
 *
 * <p>Spring 装配路径（{@code ApplicationWiring}）与 Web 触发运行路径（{@code DashboardLauncher}）都调本工厂，
 * 从而「用哪份环境变量、叠什么重试策略」只有一处定义。返回的 provider 是无状态且线程安全的：既有 {@code
 * RunCoordinator} 已经用多线程并发调用同一 provider 的 {@code create}，跨 job 复用不引入新假设。
 */
public final class LlmClientProviders {

    private LlmClientProviders() {}

    /** 以给定环境变量视图组装 provider：解析器 + 指数退避重试 + 客户端工厂方法引用。 */
    public static LlmClientProvider fromEnvironment(Map<String, String> env, ObjectMapper mapper) {
        ProviderResolver resolver = new ProviderResolver(env);
        RetryExecutor retryExecutor = new RetryExecutor(duration -> Thread.sleep(duration.toMillis()));
        LlmClientFactory factory =
                new LlmClientFactory(
                        resolver, mapper, RestClient.builder(), retryExecutor, RetryPolicy.defaults());
        return factory::create;
    }
}
