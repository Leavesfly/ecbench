package io.github.ecommercebench.llm.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.llm.provider.ResolvedProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ProviderClientsTest {

  private WireMockServer server;
  private final ObjectMapper mapper = new ObjectMapper();
  private final RetryExecutor retry = new RetryExecutor(duration -> {});
  private final RetryPolicy noDelay = new RetryPolicy(2, Duration.ZERO, 2.0, Duration.ZERO);

  @BeforeEach
  void startServer() {
    server = new WireMockServer(0);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void openAiChatMapsToolCallsAndReasoning() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"choices":[{"message":{"role":"assistant","content":"checking",
                        "reasoning_content":"reason","tool_calls":[{"id":"call_1","type":"function",
                        "function":{"name":"check_balance","arguments":"{}"}}]}}]}
                        """)));

    var client =
        new OpenAiChatClient(provider("chat"), mapper, restClientBuilder(), retry, noDelay);
    var response = client.generate(request());

    assertThat(response.content()).isEqualTo("checking");
    assertThat(response.reasoningContent()).isEqualTo("reason");
    assertThat(response.toolCalls())
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.id()).isEqualTo("call_1");
              assertThat(call.name()).isEqualTo("check_balance");
            });
    server.verify(
        postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer secret")));
  }

  @Test
  void openAiResponsesMapsReasoningAndFunctionCallItems() {
    server.stubFor(
        post(urlEqualTo("/v1/responses"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                    {"output":[
                      {"type":"reasoning","id":"r1","encrypted_content":"opaque"},
                      {"type":"message","content":[{"type":"output_text","text":"ok"}]},
                      {"type":"function_call","call_id":"call_2","name":"check_balance","arguments":"{}"}
                    ]}
                    """)));

    var client =
        new OpenAiResponsesClient(
            provider("responses"), mapper, restClientBuilder(), retry, noDelay);
    var response = client.generate(request());

    assertThat(response.content()).isEqualTo("ok");
    assertThat(response.reasoningItems()).hasSize(1);
    assertThat(response.toolCalls())
        .singleElement()
        .satisfies(call -> assertThat(call.id()).isEqualTo("call_2"));
  }

  @Test
  void anthropicMapsThinkingTextAndToolUseBlocks() {
    server.stubFor(
        post(urlEqualTo("/v1/messages"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                    {"content":[
                      {"type":"thinking","thinking":"reason","signature":"sig"},
                      {"type":"text","text":"ok"},
                      {"type":"tool_use","id":"call_3","name":"check_balance","input":{}}
                    ]}
                    """)));

    var client =
        new AnthropicClient(
            new ResolvedProvider("anthropic", "anthropic", server.baseUrl(), "secret", Map.of()),
            mapper,
            restClientBuilder(),
            retry,
            noDelay);
    var response = client.generate(request());

    assertThat(response.content()).isEqualTo("ok");
    assertThat(response.reasoningContent()).isEqualTo("reason");
    assertThat(response.toolCalls())
        .singleElement()
        .satisfies(call -> assertThat(call.id()).isEqualTo("call_3"));
    server.verify(
        postRequestedFor(urlPathEqualTo("/v1/messages"))
            .withHeader("x-api-key", equalTo("secret")));
  }

  private RestClient.Builder restClientBuilder() {
    return RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
  }

  private ResolvedProvider provider(String apiStyle) {
    return new ResolvedProvider("openai", apiStyle, server.baseUrl() + "/v1", "secret", Map.of());
  }

  private LlmRequest request() {
    ToolDefinition tool =
        new ToolDefinition("check_balance", "Check balance", mapper.createObjectNode());
    return new LlmRequest(
        "model",
        List.of(ChatMessage.user("hello")),
        List.of(tool),
        512,
        "high",
        "session",
        Map.of());
  }
}
