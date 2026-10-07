package io.github.ecommercebench.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.llm.LlmClientFactory;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.provider.ProviderResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 应用级无状态/只读单例装配。
 *
 * <p>仅定义可跨 run 共享的线程安全单例：{@link ObjectMapper}、默认 {@link LlmClientProvider}（由 {@link
 * LlmClientFactory} 适配）与 {@link RunComponentFactory}。所有 run-local 可变组件由工厂在每次 {@code create}
 * 时新建，绝不作为 Spring 单例，以保证并行 run 隔离。
 */
@Configuration
public class ApplicationWiring {

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Bean
  public LlmClientProvider llmClientProvider(ObjectMapper objectMapper) {
    ProviderResolver resolver = new ProviderResolver(System.getenv());
    RetryExecutor retryExecutor = new RetryExecutor(duration -> Thread.sleep(duration.toMillis()));
    LlmClientFactory factory =
        new LlmClientFactory(
            resolver, objectMapper, RestClient.builder(), retryExecutor, RetryPolicy.defaults());
    return factory::create;
  }

  @Bean
  public RunComponentFactory runComponentFactory(
      ObjectMapper objectMapper, LlmClientProvider llmClientProvider) {
    return new RunComponentFactory(objectMapper, llmClientProvider);
  }
}
