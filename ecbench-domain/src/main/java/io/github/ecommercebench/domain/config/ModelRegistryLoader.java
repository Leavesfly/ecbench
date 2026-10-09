package io.github.ecommercebench.domain.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.error.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 从 models_config.json 读取主模型与供应商 NPC 模型。 */
public final class ModelRegistryLoader {

  private final ObjectMapper objectMapper;

  public ModelRegistryLoader() {
    this(new ObjectMapper());
  }

  public ModelRegistryLoader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 从 models_config.json 构建 {@link ModelRegistry}。
   *
   * <p>先逐条解析 models 下各主模型，再单独解析 npc_tools；配置级的 {@code ConfigurationException} 直接透传， 其余 IO/解析异常统一包装为带文件名的配置错误。
   */
  public ModelRegistry load(Path file) {
    if (!Files.isRegularFile(file)) {
      throw new ConfigurationException("模型配置文件不存在: " + file);
    }
    try {
      JsonNode root = objectMapper.readTree(file.toFile());
      JsonNode modelsNode = requiredObject(root, "models", "根节点");
      Map<String, ModelConfig> models = new LinkedHashMap<>();
      modelsNode
          .properties()
          .forEach(entry -> models.put(entry.getKey(), parse(entry.getKey(), entry.getValue())));
      ModelConfig npc = parseNpc(requiredObject(root, "npc_tools", "根节点"));
      return new ModelRegistry(models, npc);
    } catch (ConfigurationException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new ConfigurationException(
          "读取模型配置失败: " + file + "，" + exception.getMessage(), exception);
    }
  }

  /** 解析单个主模型条目（真实模型名取 model_name）。 */
  private ModelConfig parse(String key, JsonNode node) {
    String provider = requiredText(node, "provider", key);
    String modelName = requiredText(node, "model_name", key);
    return new ModelConfig(
        key,
        provider,
        modelName,
        text(node, "api_style"),
        text(node, "effort"),
        text(node, "base_url"),
        text(node, "api_key"),
        text(node, "thinking_env"),
        map(node.get("extra_body")));
  }

  /** 解析 NPC 模型：与主模型不同，其真实模型名取自 model 而非 model_name，键固定为 npc_tools。 */
  private ModelConfig parseNpc(JsonNode node) {
    return new ModelConfig(
        "npc_tools",
        requiredText(node, "provider", "npc_tools"),
        requiredText(node, "model", "npc_tools"),
        text(node, "api_style"),
        text(node, "effort"),
        text(node, "base_url"),
        text(node, "api_key"),
        text(node, "thinking_env"),
        map(node.get("extra_body")));
  }

  private JsonNode requiredObject(JsonNode parent, String field, String owner) {
    JsonNode node = parent.get(field);
    if (node == null || !node.isObject()) {
      throw new ConfigurationException(owner + " 缺少对象字段: " + field);
    }
    return node;
  }

  /** 读取必需的非空文本字段，缺失或空白即抛配置异常（owner 用于定位是哪个模型）。 */
  private String requiredText(JsonNode node, String field, String owner) {
    String value = text(node, field);
    if (value == null || value.isBlank()) {
      throw new ConfigurationException("模型 " + owner + " 缺少字段: " + field);
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private Map<String, Object> map(JsonNode node) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    return objectMapper.convertValue(node, new TypeReference<>() {});
  }
}
