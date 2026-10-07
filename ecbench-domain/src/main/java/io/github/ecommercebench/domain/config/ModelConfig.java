package io.github.ecommercebench.domain.config;

import java.util.Map;
import java.util.Objects;

/** 单个 LLM 模型及其协议、端点和推理参数配置。 */
public record ModelConfig(
    String key,
    String provider,
    String modelName,
    String apiStyle,
    String effort,
    String baseUrl,
    String apiKeyExpression,
    String thinkingEnv,
    Map<String, Object> extraBody) {

  public ModelConfig {
    Objects.requireNonNull(key, "key 不能为空");
    Objects.requireNonNull(provider, "provider 不能为空");
    Objects.requireNonNull(modelName, "modelName 不能为空");
    apiStyle = apiStyle == null || apiStyle.isBlank() ? "chat" : apiStyle;
    extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
  }
}
