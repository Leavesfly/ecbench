package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 密钥视图合并优先级：真实环境变量优先，.env 只填补缺失项。 */
class EffectiveEnvironmentTest {

    @Test
    void processEnvironmentWinsOverFile() {
        EffectiveEnvironment env =
                new EffectiveEnvironment(Map.of("KEY", "from-process"), Map.of("KEY", "from-file"));

        assertThat(env.asMap()).containsEntry("KEY", "from-process");
    }

    @Test
    void fileFillsMissingKeysOnly() {
        EffectiveEnvironment env =
                new EffectiveEnvironment(Map.of("A", "1"), Map.of("A", "ignored", "B", "2"));

        assertThat(env.asMap()).containsExactlyInAnyOrderEntriesOf(Map.of("A", "1", "B", "2"));
    }

    @Test
    void emptySourcesYieldEmptyView() {
        assertThat(new EffectiveEnvironment(Map.of(), Map.of()).asMap()).isEmpty();
    }

    @Test
    void asMapIsImmutable() {
        EffectiveEnvironment env = new EffectiveEnvironment(Map.of("A", "1"), Map.of());

        assertThatThrownBy(() -> env.asMap().put("B", "2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultFileIsEnvUnderRepoRoot(@org.junit.jupiter.api.io.TempDir Path workingDir) {
        assertThat(EffectiveEnvironment.defaultFile(workingDir))
                .isEqualTo(workingDir.resolve(".env"));
    }

    @Test
    void loadNeverThrowsWithoutASecretsFile() {
        assertThat(EffectiveEnvironment.load().asMap()).isNotNull();
    }
}
