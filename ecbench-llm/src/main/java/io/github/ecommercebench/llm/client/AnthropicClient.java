package io.github.ecommercebench.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.model.ChatRole;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.llm.model.ReasoningItem;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.llm.provider.ResolvedProvider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.client.RestClient;

/**
 * Anthropic Messages API 客户端。
 */
public final class AnthropicClient extends AbstractJsonLlmClient {

    public AnthropicClient(
            ResolvedProvider provider,
            ObjectMapper mapper,
            RestClient.Builder builder,
            RetryExecutor retryExecutor,
            RetryPolicy retryPolicy) {
        super(provider, mapper, builder, retryExecutor, retryPolicy);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        JsonNode raw = post("/v1/messages", requestBody(request), true);
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        List<ReasoningItem> reasoning = new ArrayList<>();
        // Anthropic 把响应拆成多种 content block：text 拼接正文、thinking 累加推理并留存块、tool_use 转为工具调用。
        for (JsonNode block : raw.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> text.append(block.path("text").asText());
                case "thinking" -> {
                    thinking.append(block.path("thinking").asText());
                    reasoning.add(new ReasoningItem("thinking", block.deepCopy()));
                }
                case "tool_use" -> calls.add(
                        new ToolCall(
                                block.path("id").asText(),
                                block.path("name").asText(),
                                block.path("input").deepCopy(),
                                block.path("signature").asText(null)));
                default -> {
                }
            }
        }
        return new LlmResponse(
                text.length() == 0 ? null : text.toString(),
                calls,
                thinking.length() == 0 ? null : thinking.toString(),
                reasoning,
                raw);
    }

    /**
     * 组装 Messages API 请求体：system 角抽取合并为顶层 system 字段，其余消息按 assistant/user 归类， TOOL 角转为 tool_result 块。
     */
    private ObjectNode requestBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        String system =
                request.messages().stream()
                        .filter(message -> !message.cleared() && message.role() == ChatRole.SYSTEM)
                        .map(message -> message.content() == null ? "" : message.content())
                        .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        if (!system.isBlank()) {
            body.put("system", system);
        }
        ArrayNode messages = body.putArray("messages");
        request.messages().stream()
                .filter(message -> !message.cleared() && message.role() != ChatRole.SYSTEM)
                .forEach(
                        message -> {
                            ObjectNode node = messages.addObject();
                            node.put("role", message.role() == ChatRole.ASSISTANT ? "assistant" : "user");
                            if (message.role() == ChatRole.TOOL) {
                                ArrayNode blocks = node.putArray("content");
                                ObjectNode result = blocks.addObject();
                                result.put("type", "tool_result");
                                result.put("tool_use_id", message.toolCallId());
                                result.put("content", message.content());
                            } else {
                                node.put("content", message.content() == null ? "" : message.content());
                            }
                        });
        ArrayNode tools = body.putArray("tools");
        request
                .tools()
                .forEach(
                        tool -> {
                            ObjectNode node = tools.addObject();
                            node.put("name", tool.name());
                            node.put("description", tool.description());
                            node.set("input_schema", tool.inputSchema());
                        });
        request.extraBody().forEach((key, value) -> body.set(key, mapper.valueToTree(value)));
        mergeExtraBody(body);
        return body;
    }
}
