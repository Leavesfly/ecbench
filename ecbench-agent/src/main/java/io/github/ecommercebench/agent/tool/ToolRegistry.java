package io.github.ecommercebench.agent.tool;

import io.github.ecommercebench.llm.model.ToolDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按名称索引的工具注册表。
 *
 * <p>构造时校验名称唯一，重复即快速失败；保留插入顺序，使暴露给模型的工具 Schema 顺序稳定、可复现。
 */
public final class ToolRegistry {

  private final Map<String, EcommerceTool> byName;

  public ToolRegistry(List<EcommerceTool> tools) {
    Map<String, EcommerceTool> map = new LinkedHashMap<>();
    for (EcommerceTool tool : tools) {
      if (map.putIfAbsent(tool.name(), tool) != null) {
        throw new IllegalArgumentException("工具名称重复: " + tool.name());
      }
    }
    this.byName = Collections.unmodifiableMap(map);
  }

  public Optional<EcommerceTool> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public List<EcommerceTool> all() {
    return List.copyOf(byName.values());
  }

  /** 按注册顺序返回全部工具定义，供构建 LLM 请求时使用。 */
  public List<ToolDefinition> definitions() {
    return byName.values().stream().map(EcommerceTool::definition).toList();
  }
}
