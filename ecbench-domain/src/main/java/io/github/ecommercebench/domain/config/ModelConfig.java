package io.github.ecommercebench.domain.config;

import java.util.Map;
import java.util.Objects;

/**
 * 单个 LLM 模型及其协议、端点和推理参数配置。
 *
 * @param key 模型在注册表中的唯一键（也是 CLI --model 传入值）
 * @param provider 厂商标识，决定请求协议适配（openai/anthropic/gemini 等）
 * @param modelName 发送给 provider 的真实模型名
 * @param apiStyle 端点风格（如 openai 的 chat 与 responses），为空时默认 chat
 * @param effort 推理强度档位，高开销模型用于控制思考预算
 * @param baseUrl 自定义端点基址，兼容 OpenAI 协议的第三方网关
 * @param apiKeyExpression API Key 表达式，运行时解析为环境变量或直接值
 * @param thinkingEnv 开启思考/推理模式的环境变量名（部分模型需要）
 * @param extraBody 透传给请求体的额外参数，不可变拷贝
 */
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

  /** 紧凑构造器：锁定必填字段，并为 apiStyle 提供 chat 默认值、将 extraBody 冻结为不可变拷贝。 */
  public ModelConfig {
    Objects.requireNonNull(key, "key 不能为空");
    Objects.requireNonNull(provider, "provider 不能为空");
    Objects.requireNonNull(modelName, "modelName 不能为空");
    apiStyle = apiStyle == null || apiStyle.isBlank() ? "chat" : apiStyle;
    extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
  }
}
