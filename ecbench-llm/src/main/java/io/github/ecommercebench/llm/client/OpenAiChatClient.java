package io.github.ecommercebench.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;
import io.github.ecommercebench.llm.model.ToolCall;
import io.github.ecommercebench.llm.model.ToolDefinition;
import io.github.ecommercebench.llm.provider.ResolvedProvider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.client.RestClient;

/**
 * OpenAI Chat Completions 及兼容端点客户端。
 */
public class OpenAiChatClient extends AbstractJsonLlmClient {

    public OpenAiChatClient(
            ResolvedProvider provider,
            ObjectMapper mapper,
            RestClient.Builder builder,
            RetryExecutor retryExecutor,
            RetryPolicy retryPolicy) {
        super(provider, mapper, builder, retryExecutor, retryPolicy);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        JsonNode raw = post("/chat/completions", requestBody(request), false);
        JsonNode message = raw.path("choices").path(0).path("message");
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode node : message.path("tool_calls")) {
            JsonNode function = node.path("function");
            toolCalls.add(
                    new ToolCall(
                            node.path("id").asText(),
                            function.path("name").asText(),
                            parseArguments(function.get("arguments")),
                            node.path("thought_signature").asText(null)));
        }
        return new LlmResponse(
                message.path("content").isNull() ? null : message.path("content").asText(null),
                toolCalls,
                message.path("reasoning_content").asText(null),
                List.of(),
                raw);
    }

    private ObjectNode requestBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        if (request.effort() != null) {
            body.put("reasoning_effort", request.effort());
        }
        ArrayNode messages = body.putArray("messages");
        request.messages().stream()
                .flatMap(message -> message.forProvider().stream())
                .forEach(message -> messages.add(messageNode(message)));
        ArrayNode tools = body.putArray("tools");
        request.tools().forEach(tool -> tools.add(toolNode(tool)));
        request.extraBody().forEach((key, value) -> body.set(key, mapper.valueToTree(value)));
        mergeExtraBody(body);
        return body;
    }

    private ObjectNode messageNode(ChatMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role().wireName());
        if (message.content() == null) {
            node.putNull("content");
        } else {
            node.put("content", message.content());
        }
        if (message.toolCallId() != null) {
            node.put("tool_call_id", message.toolCallId());
        }
        if (!message.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode functionCall = calls.addObject();
                functionCall.put("id", call.id());
                functionCall.put("type", "function");
                ObjectNode function = functionCall.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.arguments().toString());
                if (call.thoughtSignature() != null) {
                    functionCall.put("thought_signature", call.thoughtSignature());
                }
            }
        }
        return node;
    }

    private ObjectNode toolNode(ToolDefinition tool) {
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("type", "function");
        ObjectNode function = wrapper.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", tool.inputSchema());
        return wrapper;
    }
}
