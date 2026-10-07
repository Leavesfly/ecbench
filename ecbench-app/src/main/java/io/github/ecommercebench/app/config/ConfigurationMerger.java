package io.github.ecommercebench.app.config;

import io.github.ecommercebench.app.cli.CliRunOptions;
import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.domain.config.ModelRegistryLoader;
import io.github.ecommercebench.domain.config.RunConfig;
import io.github.ecommercebench.domain.money.Money;
import java.nio.file.Path;
import java.util.Map;

/**
 * 按“默认值 &lt; models config &lt; env &lt; CLI”的优先级合并出 {@link BenchmarkOptions}。
 *
 * <p>端口 Python {@code agent/ecommerce_agent.py} 的配置解析：上下文参数来自 {@code ECBENCH_CONTEXT_*} 环境变量（默认
 * 120000/60000/2，与 Python 运行时一致，而非 {@code context_editor.py} 的构造回退值）；推理强度 {@code MODEL_EFFORT} 覆盖
 * models config 的 {@code effort}；tokenizer 与 models 配置路径可经 env/CLI 覆盖。为便于测试，环境变量以 {@code Map}
 * 注入而非直接读 {@code System.getenv()}。
 */
public final class ConfigurationMerger {

  private static final int DEFAULT_CONTEXT_TRIGGER = 120_000;
  private static final int DEFAULT_CONTEXT_CLEAR_AT_LEAST = 60_000;
  private static final int DEFAULT_CONTEXT_KEEP_TOOL_USE = 2;
  private static final long DEFAULT_SEED = 42L;

  private final ModelRegistryLoader registryLoader;

  public ConfigurationMerger() {
    this(new ModelRegistryLoader());
  }

  public ConfigurationMerger(ModelRegistryLoader registryLoader) {
    this.registryLoader = registryLoader;
  }

  public BenchmarkOptions merge(CliRunOptions cli, Map<String, String> env, Path workingDir) {
    ResourcePaths paths = ResourcePaths.of(workingDir);
    Path modelsConfigPath = paths.modelsConfig(env.get("ECBENCH_MODELS_CONFIG"));
    long seed = cli.seed() != null ? cli.seed() : DEFAULT_SEED;

    RunConfig runConfig =
        new RunConfig(
            cli.model(),
            cli.maxTokens(),
            cli.maxTurns(),
            cli.maxDays(),
            Money.of(cli.initialBalance()),
            Money.of(cli.dailyFee()),
            cli.maxTokenCapacity(),
            paths.tokenizer(cli.tokenizerPath(), env.get("TOKENIZER_PATH")),
            paths.dataDir(cli.dataDir()),
            paths.optional(cli.logDir()),
            paths.optional(cli.jobFile()),
            cli.runs(),
            seed);

    ContextConfig contextConfig =
        new ContextConfig(
            intEnv(env, "ECBENCH_CONTEXT_TRIGGER", DEFAULT_CONTEXT_TRIGGER),
            intEnv(env, "ECBENCH_CONTEXT_CLEAR_AT_LEAST", DEFAULT_CONTEXT_CLEAR_AT_LEAST),
            intEnv(env, "ECBENCH_CONTEXT_KEEP_TOOL_USE", DEFAULT_CONTEXT_KEEP_TOOL_USE));

    String effort = resolveEffort(env, modelsConfigPath, cli.model());
    return new BenchmarkOptions(runConfig, contextConfig, modelsConfigPath, effort);
  }

  /** env {@code MODEL_EFFORT} 优先；否则取 models config 中该模型的 effort（可能为 null）。 */
  private String resolveEffort(Map<String, String> env, Path modelsConfigPath, String model) {
    String envEffort = env.get("MODEL_EFFORT");
    if (envEffort != null && !envEffort.isBlank()) {
      return envEffort;
    }
    return registryLoader.load(modelsConfigPath).resolve(model).effort();
  }

  private static int intEnv(Map<String, String> env, String key, int fallback) {
    String value = env.get(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Integer.parseInt(value.trim());
  }
}
