package io.github.ecommercebench.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.domain.error.ConfigurationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderResolverTest {

  @Test
  void expandsConfiguredEnvironmentVariable() {
    ModelConfig config =
        new ModelConfig(
            "deepseek",
            "openai-compatible",
            "deepseek-v4",
            "chat",
            "max",
            "https://api.deepseek.com/v1",
            "${DEEPSEEK_API_KEY}",
            null,
            Map.of("thinking", Map.of("type", "enabled")));

    ResolvedProvider provider =
        new ProviderResolver(Map.of("DEEPSEEK_API_KEY", "secret")).resolve(config);

    assertThat(provider.apiKey()).isEqualTo("secret");
    assertThat(provider.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
    assertThat(provider.extraBody()).containsKey("thinking");
  }

  @Test
  void missingCredentialHasActionableError() {
    ModelConfig config =
        new ModelConfig("openai", "openai", "gpt", "chat", null, null, null, null, Map.of());

    assertThatThrownBy(() -> new ProviderResolver(Map.of()).resolve(config))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("OPENAI_API_KEY");
  }
}
