package io.github.ecommercebench.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.llm.LlmClient;
import io.github.ecommercebench.llm.model.LlmResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LlmVipConsentClassifier 测试：端口 Python `check_vip_fee_agreement` 的 yes/no 判定与容错。 */
class LlmVipConsentClassifierTest {

  private static LlmClient returning(String content) {
    return request -> new LlmResponse(content, List.of(), null, List.of(), null);
  }

  @Test
  void yesDecisionGrantsConsent() {
    LlmVipConsentClassifier classifier =
        new LlmVipConsentClassifier(returning("```json\n{\"decision\": \"yes\"}\n```"), "npc");

    assertThat(classifier.hasExplicitConsent("Please charge the membership fee now.")).isTrue();
  }

  @Test
  void noDecisionWithholdsConsent() {
    LlmVipConsentClassifier classifier =
        new LlmVipConsentClassifier(
            returning("{\"decision\": \"no\", \"reason\": \"only asking\"}"), "npc");

    assertThat(classifier.hasExplicitConsent("Is the fee refundable?")).isFalse();
  }

  @Test
  void unparseableReplyWithholdsConsent() {
    LlmVipConsentClassifier classifier =
        new LlmVipConsentClassifier(returning("maybe later"), "npc");

    assertThat(classifier.hasExplicitConsent("hmm")).isFalse();
  }

  @Test
  void llmFailureWithholdsConsent() {
    LlmClient throwing =
        request -> {
          throw new RuntimeException("boom");
        };
    LlmVipConsentClassifier classifier = new LlmVipConsentClassifier(throwing, "npc");

    assertThat(classifier.hasExplicitConsent("yes I'll pay")).isFalse();
  }
}
