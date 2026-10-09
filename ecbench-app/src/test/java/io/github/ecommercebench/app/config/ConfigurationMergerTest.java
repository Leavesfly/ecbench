package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.app.cli.CliRunOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * 配置合并优先级测试：默认值 &lt; models config &lt; env &lt; CLI。
 */
class ConfigurationMergerTest {

    private static final String CONFIG_JSON =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {
                        "gpt": {"provider": "openai", "model_name": "gpt-x", "api_style": "responses", "effort": "max"}
                      }
                    }
                    """;

    private static CliRunOptions cli(String... args) {
        CliRunOptions options = new CliRunOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }

    private static Path workdirWithConfig() throws Exception {
        Path dir = Files.createTempDirectory("ecbench-cfg");
        Files.writeString(dir.resolve("models_config.json"), CONFIG_JSON);
        return dir;
    }

    @Test
    void appliesDefaultsAndModelsConfigEffort() throws Exception {
        Path wd = workdirWithConfig();

        BenchmarkOptions options = new ConfigurationMerger().merge(cli("--model", "gpt"), Map.of(), wd);

        assertThat(options.runConfig().maxDays()).isEqualTo(365);
        assertThat(options.runConfig().maxTokens()).isEqualTo(16384);
        assertThat(options.runConfig().maxTurns()).isEqualTo(4000);
        assertThat(options.runConfig().runs()).isEqualTo(1);
        assertThat(options.runConfig().seed()).isEqualTo(42L);
        assertThat(options.contextConfig().trigger()).isEqualTo(120000);
        assertThat(options.contextConfig().clearAtLeast()).isEqualTo(60000);
        assertThat(options.contextConfig().keepToolUse()).isEqualTo(2);
        assertThat(options.effort()).isEqualTo("max");
        assertThat(options.modelsConfigPath()).isEqualTo(wd.resolve("models_config.json"));
        assertThat(options.runConfig().dataDir()).isEqualTo(wd.resolve("data"));
    }

    @Test
    void cliOverridesDefaults() throws Exception {
        Path wd = workdirWithConfig();

        BenchmarkOptions options =
                new ConfigurationMerger()
                        .merge(
                                cli(
                                        "--model", "gpt",
                                        "--max-days", "10",
                                        "--runs", "3",
                                        "--initial-balance", "250.5",
                                        "--seed", "9"),
                                Map.of(),
                                wd);

        assertThat(options.runConfig().maxDays()).isEqualTo(10);
        assertThat(options.runConfig().runs()).isEqualTo(3);
        assertThat(options.runConfig().initialBalance().amount().doubleValue()).isEqualTo(250.5);
        assertThat(options.runConfig().seed()).isEqualTo(9L);
    }

    @Test
    void envOverridesContextDefaultsAndModelsConfigPath() throws Exception {
        Path wd = workdirWithConfig();
        Path custom = wd.resolve("custom_models.json");
        Files.writeString(custom, CONFIG_JSON);
        Map<String, String> env = new HashMap<>();
        env.put("ECBENCH_CONTEXT_TRIGGER", "5000");
        env.put("ECBENCH_CONTEXT_CLEAR_AT_LEAST", "1000");
        env.put("ECBENCH_CONTEXT_KEEP_TOOL_USE", "4");
        env.put("ECBENCH_MODELS_CONFIG", custom.toString());

        BenchmarkOptions options = new ConfigurationMerger().merge(cli("--model", "gpt"), env, wd);

        assertThat(options.contextConfig().trigger()).isEqualTo(5000);
        assertThat(options.contextConfig().clearAtLeast()).isEqualTo(1000);
        assertThat(options.contextConfig().keepToolUse()).isEqualTo(4);
        assertThat(options.modelsConfigPath()).isEqualTo(custom);
    }

    @Test
    void envModelEffortOverridesModelsConfig() throws Exception {
        Path wd = workdirWithConfig();

        BenchmarkOptions options =
                new ConfigurationMerger().merge(cli("--model", "gpt"), Map.of("MODEL_EFFORT", "low"), wd);

        assertThat(options.effort()).isEqualTo("low");
    }
}
