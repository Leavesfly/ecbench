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
 *
 * <p>请求发往 {@code /responses}（provider 的 baseUrl 已含 /v1），消息用 input 列表而非 messages；
 * 鉴权走父类默认的 Bearer 方式。响应 output 是混合 item 列表，需按 type 拆出 reasoning、
 * message 与 function_call 三类。
 */
public final class OpenAiResponsesClient extends AbstractJsonLlmClient {

    /** 只是把连接与重试依赖转交父类，本类不额外持有状态。 */
    public OpenAiResponsesClient(
            ResolvedProvider provider,
            ObjectMapper mapper,
            RestClient.Builder builder,
            RetryExecutor retryExecutor,
            RetryPolicy retryPolicy) {
        super(provider, mapper, builder, retryExecutor, retryPolicy);
    }

    /**
     * 调用 Responses API 并将响应归一为统一模型。
     *
     * <p>reasoning item 原样留存供下轮回放，message 拼接其中所有 output_text 段落，
     * function_call 转为标准工具调用；该协议没有独立的推理文本字段，故 reasoningContent 恒为 null。
     *
     * @param request 统一请求模型，提供 model、max_output_tokens、effort、消息与工具声明
     * @return 统一响应模型（正文/工具调用/推理项/原始 JSON）
     */
    @Override
    public LlmResponse generate(LlmRequest request) {
        // anthropic=false：走标准 Authorization: Bearer 鉴权
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
                // function_call 的 arguments 是 JSON 字符串，需经父类 parseArguments 归一为对象
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
        // reasoningContent 留空：Responses API 仅返回结构化 reasoning item，无独立推理文本
        return new LlmResponse(text, calls, null, reasoning, raw);
    }

    /**
     * 组装 Responses API 请求体：用 input 列表扁平化消息，reasoning.effort 控制推理档位，
     * tools 以 function 型声明。
     *
     * @param request 统一请求模型；已标记 cleared 的消息经 {@code forProvider()} 过滤后不会下发
     * @return 可直接序列化发送的 Responses API 请求体
     */
    private ObjectNode requestBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_output_tokens", request.maxTokens());
        if (request.effort() != null) {
            body.putObject("reasoning").put("effort", request.effort());
        }
        ArrayNode input = body.putArray("input");
        // 消息扁平化为 input 列表：角色直接用 wireName（含 system/tool），内容 null 归一为空串
        request.messages().stream()
                .flatMap(message -> message.forProvider().stream())
                .forEach(
                        message -> {
                            ObjectNode item = input.addObject();
                            item.put("role", message.role().wireName());
                            item.put("content", message.content() == null ? "" : message.content());
                        });
        ArrayNode tools = body.putArray("tools");
        // 工具以扁平的 function 型声明：name/description/parameters 直接放对象上，不套 chat 的 function 包装
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
        // 两级透传：先写请求级 extraBody，再由 provider 级 extraBody 覆盖同名键
        request.extraBody().forEach((key, value) -> body.set(key, mapper.valueToTree(value)));
        mergeExtraBody(body);
        return body;
    }
}
