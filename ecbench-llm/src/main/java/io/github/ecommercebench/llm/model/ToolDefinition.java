package io.github.ecommercebench.llm.model;

import com.fasterxml.jackson.databind.JsonNode;

/** 暴露给模型的函数工具定义。 */
public record ToolDefinition(String name, String description, JsonNode inputSchema) {}
