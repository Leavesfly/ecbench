package io.github.ecommercebench.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.llm.model.ReasoningItem;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.llm.provider.ResolvedProvider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.client.RestClient;

/**
 * OpenAI Responses API 客户端，保留 reasoning item 以支持下一轮回放。
 */
public final class OpenAiResponsesClient extends AbstractJsonLlmClient {

    public OpenAiResponsesClient(
            ResolvedProvider provider,
            ObjectMapper mapper,
            RestClient.Builder builder,
            RetryExecutor retryExecutor,
            RetryPolicy retryPolicy) {
        super(provider, mapper, builder, retryExecutor, retryPolicy);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        JsonNode raw = post("/responses", requestBody(request), false);
        StringBuilder content = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        List<ReasoningItem> reasoning = new ArrayList<>();
        // Responses API 的 output 是混合 item 列表：reasoning 原样留存供下轮回放，message 取 output_text，function_call 转工具调用。
        for (JsonNode item : raw.path("output")) {
            switch (item.path("type").asText()) {
                case "reasoning" -> reasoning.add(new ReasoningItem("reasoning", item.deepCopy()));
                case "message" -> item.path("content")
                        .forEach(
                                part -> {
                                    if ("output_text".equals(part.path("type").asText())) {
                                        content.append(part.path("text").asText());
                                    }
                                });
                case "function_call" -> calls.add(
                        new ToolCall(
                                item.path("call_id").asText(),
                                item.path("name").asText(),
                                parseArguments(item.get("arguments")),
                                item.path("thought_signature").asText(null)));
                default -> {
                }
            }
        }
        String text = content.length() == 0 ? null : content.toString();
        return new LlmResponse(text, calls, null, reasoning, raw);
    }

    /** 组装 Responses API 请求体：用 input 列表扁平化消息，reasoning.effort 控制推理档位，tools 以 function 型声明。 */
    private ObjectNode requestBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_output_tokens", request.maxTokens());
        if (request.effort() != null) {
            body.putObject("reasoning").put("effort", request.effort());
        }
        ArrayNode input = body.putArray("input");
        request.messages().stream()
                .flatMap(message -> message.forProvider().stream())
                .forEach(
                        message -> {
                            ObjectNode item = input.addObject();
                            item.put("role", message.role().wireName());
                            item.put("content", message.content() == null ? "" : message.content());
                        });
        ArrayNode tools = body.putArray("tools");
        request
                .tools()
                .forEach(
                        tool -> {
                            ObjectNode node = tools.addObject();
                            node.put("type", "function");
                            node.put("name", tool.name());
                            node.put("description", tool.description());
                            node.set("parameters", tool.inputSchema());
                        });
        request.extraBody().forEach((key, value) -> body.set(key, mapper.valueToTree(value)));
        mergeExtraBody(body);
        return body;
    }
}
