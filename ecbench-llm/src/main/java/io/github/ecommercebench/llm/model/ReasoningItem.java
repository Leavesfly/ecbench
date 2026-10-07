package io.github.ecommercebench.llm.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Provider 返回且下轮需要原样回放的推理项。 */
public record ReasoningItem(String type, JsonNode payload) {}
