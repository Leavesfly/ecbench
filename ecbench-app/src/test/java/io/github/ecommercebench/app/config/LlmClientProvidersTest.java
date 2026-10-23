package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.domain.error.ConfigurationException;
import io.github.ecommercebench.llm.provider.ProviderResolver;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** provider 预设 env 名暴露与 provider 构造工厂的离线契约（不发任何网络请求）。 */
class LlmClientProvidersTest {

    private static ModelConfig config(String provider, String apiKeyExpression) {
        return new ModelConfig(
                "k",
                provider,
                "model-x",
                "chat",
                null,
                "openai-compatible".equals(provider) ? "https://example.invalid/v1" : null,
                apiKeyExpression,
                null,
                null);
    }

    @Test
    void exposesPresetKeyEnvironmentNames() {
        ProviderResolver resolver = new ProviderResolver(Map.of());

        assertThat(resolver.keyEnvironmentFor("openai")).isEqualTo("OPENAI_API_KEY");
        assertThat(resolver.keyEnvironmentFor("anthropic")).isEqualTo("ANTHROPIC_API_KEY");
        assertThat(resolver.keyEnvironmentFor("google")).isEqualTo("GEMINI_API_KEY");
        assertThat(resolver.keyEnvironmentFor("openrouter")).isEqualTo("OPENROUTER_API_KEY");
        assertThat(resolver.keyEnvironmentFor("openai-compatible")).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void unknownProviderHasNoPresetKeyEnvironment() {
        assertThat(new ProviderResolver(Map.of()).keyEnvironmentFor("nope")).isNull();
    }

    @Test
    void providerFromEnvironmentCreatesClientWhenKeyPresent() {
        LlmClientProvider provider =
                LlmClientProviders.fromEnvironment(Map.of("OPENAI_API_KEY", "sk-test"), new ObjectMapper());

        assertThat(provider.create(config("openai", null))).isNotNull();
    }

    @Test
    void providerFromEnvironmentRejectsMissingKey() {
        LlmClientProvider provider = LlmClientProviders.fromEnvironment(Map.of(), new ObjectMapper());

        assertThatThrownBy(() -> provider.create(config("openai", null)))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void expressionKeyIsResolvedFromTheGivenEnvironment() {
        LlmClientProvider provider =
                LlmClientProviders.fromEnvironment(Map.of("DASHSCOPE_API_KEY", "sk-d"), new ObjectMapper());

        assertThat(provider.create(config("openai-compatible", "${DASHSCOPE_API_KEY}"))).isNotNull();
    }
}
