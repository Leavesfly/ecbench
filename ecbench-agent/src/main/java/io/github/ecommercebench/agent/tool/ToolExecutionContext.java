package io.github.ecommercebench.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.simulation.SimulationEngine;

/**
 * 工具执行期的公共上下文。
 *
 * <p>只承载所有工具共享的协作者：仿真状态唯一写入口与 JSON 编解码器。工具若需要专属协作者（如记忆、Chatbox 编排）， 由工具自身在构造时持有，不放入本上下文，避免上下文膨胀。
 */
public record ToolExecutionContext(SimulationEngine engine, ObjectMapper mapper) {}
