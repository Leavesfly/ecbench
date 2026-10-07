package io.github.ecommercebench.llm.provider;

import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.domain.error.ConfigurationException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按 providers.py 的预设补全 base URL、API 风格和环境变量密钥。 */
public final class ProviderResolver {

  private static final Pattern ENV_REFERENCE = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$");
  private static final Map<String, Preset> PRESETS =
      Map.of(
          "openai", new Preset("https://api.openai.com/v1", "OPENAI_API_KEY", "chat"),
          "anthropic", new Preset("https://api.anthropic.com", "ANTHROPIC_API_KEY", "anthropic"),
          "google",
              new Preset(
                  "https://generativelanguage.googleapis.com/v1beta/openai",
                  "GEMINI_API_KEY",
                  "chat"),
          "openrouter", new Preset("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "chat"),
          "openai-compatible", new Preset(null, "OPENAI_API_KEY", "chat"));

  private final Map<String, String> environment;

  public ProviderResolver(Map<String, String> environment) {
    this.environment = Map.copyOf(environment);
  }

  public ResolvedProvider resolve(ModelConfig config) {
    String providerName = inferProvider(config);
    Preset preset = PRESETS.get(providerName);
    if (preset == null) {
      throw new ConfigurationException("未知 LLM provider: " + providerName);
    }
    String baseUrl = expand(config.baseUrl() == null ? preset.baseUrl() : config.baseUrl());
    if ("openai-compatible".equals(providerName) && (baseUrl == null || baseUrl.isBlank())) {
      throw new ConfigurationException("openai-compatible 模型必须配置 base_url");
    }
    String apiKey =
        config.apiKeyExpression() == null
            ? environment.get(preset.keyEnvironment())
            : expand(config.apiKeyExpression());
    if (apiKey == null || apiKey.isBlank()) {
      throw new ConfigurationException("缺少环境变量 " + preset.keyEnvironment());
    }
    String apiStyle =
        config.apiStyle() == null || config.apiStyle().isBlank()
            ? preset.apiStyle()
            : config.apiStyle();
    return new ResolvedProvider(providerName, apiStyle, baseUrl, apiKey, config.extraBody());
  }

  private String inferProvider(ModelConfig config) {
    if (config.provider() != null && !config.provider().isBlank()) {
      return config.provider().trim().toLowerCase(java.util.Locale.ROOT);
    }
    String base =
        config.baseUrl() == null ? "" : config.baseUrl().toLowerCase(java.util.Locale.ROOT);
    if (base.contains("openrouter.ai")) return "openrouter";
    if (base.contains("anthropic.com")) return "anthropic";
    if (base.contains("openai.com")) return "openai";
    if (base.contains("googleapis.com")) return "google";
    if (!base.isBlank()) return "openai-compatible";
    String model = config.modelName().toLowerCase(java.util.Locale.ROOT);
    if (model.startsWith("claude")) return "anthropic";
    if (model.startsWith("gemini")) return "google";
    if (model.startsWith("gpt-") || model.matches("o[134].*")) return "openai";
    return "openai-compatible";
  }

  private String expand(String value) {
    if (value == null) {
      return null;
    }
    Matcher matcher = ENV_REFERENCE.matcher(value.trim());
    if (!matcher.matches()) {
      return value;
    }
    String resolved = environment.get(matcher.group(1));
    if (resolved == null || resolved.isBlank()) {
      throw new ConfigurationException("缺少环境变量 " + matcher.group(1));
    }
    return resolved;
  }

  private record Preset(String baseUrl, String keyEnvironment, String apiStyle) {}
}
