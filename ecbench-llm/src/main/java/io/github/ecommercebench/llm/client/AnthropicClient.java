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
 *
 * <p>请求固定发往 {@code /v1/messages}（预设 baseUrl 为 {@code https://api.anthropic.com}，不含 /v1），
 * x-api-key 与 anthropic-version 鉴权头由父类 {@code post(..., true)} 统一添加；
 * 响应是 content block 数组，需按 type 归并成统一的文本、推理与工具调用三部分。
 */
public final class AnthropicClient extends AbstractJsonLlmClient {

    /** 只是把连接与重试依赖转交父类，本类不额外持有状态。 */
    public AnthropicClient(
            ResolvedProvider provider,
            ObjectMapper mapper,
            RestClient.Builder builder,
            RetryExecutor retryExecutor,
            RetryPolicy retryPolicy) {
        super(provider, mapper, builder, retryExecutor, retryPolicy);
    }

    /**
     * 调用 Messages API 并将响应归一为统一模型。
     *
     * <p>content 数组中的 text 拼接为正文、thinking 累加推理文本，
     * 并留存原始块供下轮回放、tool_use 转为标准工具调用；对应内容为空时字段置 null，
     * 原始响应整体保留在 rawResponse 中供审计。
     *
     * @param request 统一请求模型，提供 model、max_tokens、消息与工具声明
     * @return 统一响应模型（正文/工具调用/推理内容/原始 JSON）
     */
    @Override
    public LlmResponse generate(LlmRequest request) {
        // anthropic=true 让父类改用 x-api-key + anthropic-version 而非 Bearer 鉴权
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
                    // thinking 块的 signature 必须原样留存，下一轮回放时 Provider 会校验
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
     * 组装 Messages API 请求体：把 system 角色抽取合并为顶层 {@code system} 字段，
     * 其余消息按 assistant/user 归类（TOOL 也记为 user），并 TOOL 消息转为 {@code tool_result} 内容块。
     *
     * @param request 统一请求模型；已标记 cleared 的消息（上下文清理产物）会被跳过
     * @return 可直接序列化发送的 Messages API 请求体
     */
    private ObjectNode requestBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        // Anthropic 只接受顶层单个 system 字符串，因此把多条 SYSTEM 消息按换行拼成一段
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
                                // 工具结果须以 user 消息内的 tool_result 块回传，并携带对应 tool_use_id
                                ArrayNode blocks = node.putArray("content");
                                ObjectNode result = blocks.addObject();
                                result.put("type", "tool_result");
                                result.put("tool_use_id", message.toolCallId());
                                result.put("content", message.content());
                            } else {
                                // 普通消息直接把文本作为 content；null 归一为空串以免 Provider 报参数错误
                                node.put("content", message.content() == null ? "" : message.content());
                            }
                        });
        // 工具以 tools 数组声明，input_schema 沿用内部 JSON Schema，不做结构转换
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
        // 两级透传：先写请求级 extraBody，再由 provider 级 extraBody 覆盖同名键
        request.extraBody().forEach((key, value) -> body.set(key, mapper.valueToTree(value)));
        mergeExtraBody(body);
        return body;
    }
}
