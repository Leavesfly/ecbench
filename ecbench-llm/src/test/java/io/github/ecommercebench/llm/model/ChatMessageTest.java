package io.github.ecommercebench.llm.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatMessageTest {

  @Test
  void toolMessagePreservesCallId() {
    ChatMessage message = ChatMessage.tool("call-1", "{\"success\":true}");

    assertThat(message.role()).isEqualTo(ChatRole.TOOL);
    assertThat(message.toolCallId()).isEqualTo("call-1");
  }

  @Test
  void markedMessageIsExcludedFromProviderViewButRetainedInHistory() {
    ChatMessage message = ChatMessage.user("old").markCleared();

    assertThat(message.cleared()).isTrue();
    assertThat(message.forProvider()).isEmpty();
    assertThat(message.content()).isEqualTo("old");
  }
}
