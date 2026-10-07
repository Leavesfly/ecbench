package io.github.ecommercebench.domain.config;

import io.github.ecommercebench.domain.error.ConfigurationException;
import java.util.Map;
import java.util.Objects;

/** 可按 CLI 模型键查询的只读模型注册表。 */
public final class ModelRegistry {

  private final Map<String, ModelConfig> models;
  private final ModelConfig npcModel;

  public ModelRegistry(Map<String, ModelConfig> models, ModelConfig npcModel) {
    this.models = Map.copyOf(models);
    this.npcModel = Objects.requireNonNull(npcModel, "npcModel 不能为空");
  }

  public Map<String, ModelConfig> models() {
    return models;
  }

  public ModelConfig npcModel() {
    return npcModel;
  }

  public ModelConfig resolve(String modelKey) {
    ModelConfig result = models.get(modelKey);
    if (result == null) {
      throw new ConfigurationException("models_config.json 中不存在模型: " + modelKey);
    }
    return result;
  }
}
