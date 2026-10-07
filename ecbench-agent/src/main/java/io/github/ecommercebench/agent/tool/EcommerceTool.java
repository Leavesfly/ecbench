package io.github.ecommercebench.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.llm.model.ToolDefinition;

/**
 * 单个电商工具的统一契约。
 *
 * <p>实现只负责把入参映射为领域调用，并把领域结果映射为与 Python 工具逐键兼容的 JSON；不得复制业务计算。 参数错误抛 {@link
 * IllegalArgumentException}，业务规则冲突由仿真层抛 {@link
 * io.github.ecommercebench.simulation.error.BusinessRuleException}，二者都被工具管理器转成稳定的错误 JSON。
 */
public interface EcommerceTool {

  /** 工具名称，必须与 Python `TOOL_REGISTRY` 注册名一致。 */
  String name();

  /** 暴露给模型的函数定义（名称、描述、输入 JSON Schema）。 */
  ToolDefinition definition();

  /** 执行工具并返回可序列化的 JSON 对象结果。 */
  ObjectNode execute(JsonNode args, ToolExecutionContext context);
}
