package io.github.ecommercebench.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.llm.error.ProviderException;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.provider.ResolvedProvider;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** JSON LLM 客户端公共的同步 HTTP 与错误分类逻辑。 */
abstract class AbstractJsonLlmClient implements LlmClient {

  protected final ResolvedProvider provider;
  protected final ObjectMapper mapper;
  private final RestClient client;
  private final RetryExecutor retryExecutor;
  private final RetryPolicy retryPolicy;

  AbstractJsonLlmClient(
      ResolvedProvider provider,
      ObjectMapper mapper,
      RestClient.Builder builder,
      RetryExecutor retryExecutor,
      RetryPolicy retryPolicy) {
    this.provider = provider;
    this.mapper = mapper;
    this.client = builder.baseUrl(provider.baseUrl()).build();
    this.retryExecutor = retryExecutor;
    this.retryPolicy = retryPolicy;
  }

  protected JsonNode post(String path, ObjectNode body, boolean anthropic) {
    return retryExecutor.execute(
        () -> {
          try {
            RestClient.RequestBodySpec request =
                client
                    .post()
                    .uri(path)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            request =
                anthropic
                    ? request
                        .header("x-api-key", provider.apiKey())
                        .header("anthropic-version", "2023-06-01")
                    : request.header("Authorization", "Bearer " + provider.apiKey());
            JsonNode response = request.body(body).retrieve().body(JsonNode.class);
            if (response == null) {
              throw new ProviderException(provider.name(), 0, true, "Provider 返回空响应");
            }
            return response;
          } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
            throw new ProviderException(
                provider.name(), status, retryable, "Provider HTTP 错误: " + status);
          } catch (RestClientException exception) {
            throw new ProviderException(provider.name(), true, "Provider 网络调用失败", exception);
          }
        },
        retryPolicy);
  }

  protected JsonNode parseArguments(JsonNode value) {
    if (value == null || value.isNull()) {
      return mapper.createObjectNode();
    }
    if (!value.isTextual()) {
      return value;
    }
    try {
      return mapper.readTree(value.asText());
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      return mapper.createObjectNode().put("_raw", value.asText());
    }
  }

  protected void mergeExtraBody(ObjectNode body) {
    provider.extraBody().forEach((key, value) -> body.set(key, mapper.valueToTree(value)));
  }
}
