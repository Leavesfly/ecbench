package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 密钥就绪判定：${VAR} 表达式与 provider 预设两条推断路径，以及 NPC 模型的独立判定。 */
class ModelKeyGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {
                        "gpt": {"provider": "openai", "model_name": "gpt-x"},
                        "qwen": {"provider": "openai-compatible", "model_name": "qwen-x",
                                 "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                 "api_key": "${DASHSCOPE_API_KEY}"}
                      }
                    }
                    """;

    private static ModelKeyGuard guard(Path dir, Map<String, String> env) throws Exception {
        Files.writeString(dir.resolve("models_config.json"), CONFIG);
        return new ModelKeyGuard(dir.resolve("models_config.json"), env, MAPPER);
    }

    @Test
    void presetProviderUsesItsDefaultVariable(@TempDir Path dir) throws Exception {
        assertThat(guard(dir, Map.of()).missingEnv("gpt")).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void expressionProviderUsesTheDeclaredVariableNotThePreset(@TempDir Path dir) throws Exception {
        // openai-compatible 的预设是 OPENAI_API_KEY，但该模型声明了 ${DASHSCOPE_API_KEY}，必须报后者。
        assertThat(guard(dir, Map.of()).missingEnv("qwen")).isEqualTo("DASHSCOPE_API_KEY");
    }

    @Test
    void presentNonBlankValueMeansReady(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("OPENAI_API_KEY", "sk-x", "DASHSCOPE_API_KEY", "sk-y"));

        assertThat(guard.missingEnv("gpt")).isNull();
        assertThat(guard.missingEnv("qwen")).isNull();
    }

    @Test
    void blankValueCountsAsMissing(@TempDir Path dir) throws Exception {
        assertThat(guard(dir, Map.of("OPENAI_API_KEY", "   ")).missingEnv("gpt"))
                .isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void npcKeyIsJudgedIndependently(@TempDir Path dir) throws Exception {
        assertThat(guard(dir, Map.of("DASHSCOPE_API_KEY", "sk-y")).npcMissingEnv())
                .isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void requireReadyNamesTheMissingVariable(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of());

        assertThatThrownBy(() -> guard.requireReady("qwen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void requireReadyAlsoChecksTheNpcModel(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("DASHSCOPE_API_KEY", "sk-y"));

        assertThatThrownBy(() -> guard.requireReady("qwen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageContaining("npc_tools");
    }

    @Test
    void requireReadyPassesWhenBothKeysPresent(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("OPENAI_API_KEY", "sk-x", "DASHSCOPE_API_KEY", "sk-y"));

        guard.requireReady("qwen");
        guard.requireReady("gpt");
    }

    @Test
    void unknownModelKeyIsRejectedWithTheAvailableCount(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("OPENAI_API_KEY", "sk-x"));

        assertThatThrownBy(() -> guard.requireReady("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("2");
    }

    @Test
    void registryIsExposedForListing(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of());

        assertThat(guard.registry().models()).containsOnlyKeys("gpt", "qwen");
        assertThat(guard.registry().npcModel().modelName()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void nullConfigPathYieldsEmptyRegistry() {
        ModelKeyGuard guard = new ModelKeyGuard(null, Map.of(), MAPPER);

        assertThat(guard.registry().models()).isEmpty();
        assertThatThrownBy(() -> guard.requireReady("gpt")).isInstanceOf(IllegalArgumentException.class);
    }
}
