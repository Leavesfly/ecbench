package io.github.ecommercebench.llm.model;

import com.fasterxml.jackson.databind.JsonNode;

/** LLM 请求执行一个函数工具的标准化表示。 */
public record ToolCall(String id, String name, JsonNode arguments, String thoughtSignature) {}
