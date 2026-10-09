package io.github.ecommercebench.domain.config;

import io.github.ecommercebench.domain.error.ConfigurationException;
import java.util.Map;
import java.util.Objects;

/** 可按 CLI 模型键查询的只读模型注册表。 */
public final class ModelRegistry {

  private final Map<String, ModelConfig> models;
  private final ModelConfig npcModel;

  /** 以防御性拷贝封装模型表；npcModel 为供应商 NPC 对话所使用的模型，不得为空。 */
  public ModelRegistry(Map<String, ModelConfig> models, ModelConfig npcModel) {
    this.models = Map.copyOf(models);
    this.npcModel = Objects.requireNonNull(npcModel, "npcModel 不能为空");
  }

  /** 返回不可变的全部主模型配置（键为 CLI 模型键）。 */
  public Map<String, ModelConfig> models() {
    return models;
  }

  /** 返回供应商 NPC（谈判对手渲染）使用的模型配置。 */
  public ModelConfig npcModel() {
    return npcModel;
  }

  /** 按模型键查找配置；不存在时立即抛 {@code ConfigurationException}，而非返回 null。 */
  public ModelConfig resolve(String modelKey) {
    ModelConfig result = models.get(modelKey);
    if (result == null) {
      throw new ConfigurationException("models_config.json 中不存在模型: " + modelKey);
    }
    return result;
  }
}
