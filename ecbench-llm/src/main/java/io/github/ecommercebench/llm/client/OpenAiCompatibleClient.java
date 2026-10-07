package io.github.ecommercebench.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.provider.ResolvedProvider;
import org.springframework.web.client.RestClient;

/** OpenAI wire-format 兼容服务客户端。 */
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
