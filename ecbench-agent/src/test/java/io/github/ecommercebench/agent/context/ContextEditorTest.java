package io.github.ecommercebench.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextEditorTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void clearsOldestCompleteToolGroupAndProtectsLatestGroup() {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(ChatMessage.system("system"));
    messages.add(ChatMessage.user("first"));
    messages.add(assistantCall("c1"));
    messages.add(ChatMessage.tool("c1", "result-1"));
    messages.add(assistantCall("c2"));
    messages.add(ChatMessage.tool("c2", "result-2"));

    ContextEditResult result =
        new ContextEditor(message -> 10).edit(messages, new ContextConfig(50, 20, 1), 100);

    assertThat(result.tokensFreed()).isEqualTo(20);
    assertThat(result.messages().get(0).cleared()).isFalse();
    assertThat(result.messages().get(1).cleared()).isFalse();
    assertThat(result.messages().get(2).cleared()).isTrue();
    assertThat(result.messages().get(3).cleared()).isTrue();
    assertThat(result.messages().get(4).cleared()).isFalse();
    assertThat(result.warning()).contains("20 oldest tokens cleared");
  }

  @Test
  void doesNotClearWhenBelowTrigger() {
    List<ChatMessage> messages = List.of(ChatMessage.system("system"), ChatMessage.user("first"));

    ContextEditResult result =
        new ContextEditor(message -> 10).edit(messages, ContextConfig.defaults(), 128_000);

    assertThat(result.tokensFreed()).isZero();
    assertThat(result.warning()).contains("20/128000");
  }

  private ChatMessage assistantCall(String id) {
    return ChatMessage.assistant(
        "call",
        List.of(new ToolCall(id, "check_balance", mapper.createObjectNode(), null)),
        null,
        List.of());
  }
}
