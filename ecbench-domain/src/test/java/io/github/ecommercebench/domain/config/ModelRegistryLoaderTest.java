package io.github.ecommercebench.domain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ecommercebench.domain.error.ConfigurationException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ModelRegistryLoaderTest {

  @Test
  void loadsRepositoryRegistryAndResolvesModels() {
    Path config = Path.of(System.getProperty("user.dir"), "..", "models_config.json").normalize();

    ModelRegistry registry = new ModelRegistryLoader().load(config);
    ModelConfig model = registry.resolve("gpt-5.6-sol");

    assertThat(registry.models()).hasSize(18);
    assertThat(model.provider()).isEqualTo("openai");
    assertThat(model.modelName()).isEqualTo("gpt-5.6-sol");
    assertThat(model.apiStyle()).isEqualTo("responses");
    assertThat(model.effort()).isEqualTo("max");
    assertThat(registry.npcModel().modelName()).isEqualTo("gpt-4o-mini");
  }

  @Test
  void preservesVendorSpecificExtraBody() {
    Path config = Path.of(System.getProperty("user.dir"), "..", "models_config.json").normalize();

    ModelConfig model = new ModelRegistryLoader().load(config).resolve("glm-5.2-max");

    assertThat(model.extraBody()).containsKey("thinking");
  }

  @Test
  void unknownModelHasActionableError() {
    Path config = Path.of(System.getProperty("user.dir"), "..", "models_config.json").normalize();
    ModelRegistry registry = new ModelRegistryLoader().load(config);

    assertThatThrownBy(() -> registry.resolve("not-present"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("not-present");
  }

  @Test
  void runAndContextDefaultsMatchPython() {
    RunConfig run = RunConfig.defaults();
    ContextConfig context = ContextConfig.defaults();

    assertThat(run.maxTokens()).isEqualTo(16_384);
    assertThat(run.maxTurns()).isEqualTo(4_000);
    assertThat(run.maxDays()).isEqualTo(365);
    assertThat(run.initialBalance().toString()).isEqualTo("100000.00");
    assertThat(run.maxTokenCapacity()).isEqualTo(128_000);
    assertThat(context.trigger()).isEqualTo(90_000);
    assertThat(context.clearAtLeast()).isEqualTo(34_000);
    assertThat(context.keepToolUse()).isEqualTo(2);
  }
}
