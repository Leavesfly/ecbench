package io.github.ecommercebench.llm.provider;

import java.util.Map;

/** 已补全端点与凭据的 Provider 配置。 */
public record ResolvedProvider(
    String name, String apiStyle, String baseUrl, String apiKey, Map<String, Object> extraBody) {
  public ResolvedProvider {
    extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
  }
}
