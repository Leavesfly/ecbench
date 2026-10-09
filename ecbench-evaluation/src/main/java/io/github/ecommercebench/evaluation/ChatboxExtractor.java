package io.github.ecommercebench.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.model.ChatboxConversation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从消息 JSONL 提取按供应商分组的 chatbox 会话，端口 Python {@code extract_chatbox.extract_chatbox_conversations}。
 *
 * <p>chatbox 工具是同步的：供应商回复与 agent 去信在同一条 tool 结果中，用 {@code tool_call_id} 关联。对每条 assistant 的 chatbox
 * 调用， 解析 arguments 取 {@code uid}（兼容旧 {@code to_email}），记录 tool_call_id→supplier 并把原始行归入该供应商；对
 * message 为 {@code message_sent}/{@code supplier_bankrupt} 的 tool 结果，按 tool_call_id
 * 归入对应供应商。原始行逐字保留、按消息序排序。 与 Python 一致，半写入的坏行被跳过而非中断整文件。
 */
public final class ChatboxExtractor {

    private static final Pattern SYSTEM_WARNING =
            Pattern.compile("<system_warning>.*?</system_warning>", Pattern.DOTALL);

    private final ObjectMapper mapper;

    public ChatboxExtractor() {
        this(new ObjectMapper());
    }

    public ChatboxExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, ChatboxConversation> extract(Path messagesJsonl) {
        List<String> lines;
        try {
            lines = Files.readAllLines(messagesJsonl, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取消息 JSONL: " + messagesJsonl, exception);
        }
        Map<String, String> tcidToSupplier = new LinkedHashMap<>();
        Map<String, List<String>> supplierLines = new LinkedHashMap<>();

        for (String line : lines) {
            String raw = line.trim();
            if (raw.isEmpty()) {
                continue;
            }
            JsonNode msg;
            try {
                msg = mapper.readTree(raw);
            } catch (IOException exception) {
                continue; // 截断/半写入行：跳过，与 Python 一致
            }
            String role = msg.path("role").asText("");
            if ("assistant".equals(role) && msg.path("tool_calls").isArray()) {
                collectCalls(msg, raw, tcidToSupplier, supplierLines);
            } else if ("tool".equals(role)) {
                collectResult(msg, raw, tcidToSupplier, supplierLines);
            }
        }

        Map<String, ChatboxConversation> result = new LinkedHashMap<>();
        List<String> suppliers = new ArrayList<>(supplierLines.keySet());
        suppliers.sort(String::compareTo);
        for (String supplier : suppliers) {
            result.put(supplier, new ChatboxConversation(supplier, supplierLines.get(supplier)));
        }
        return result;
    }

    private void collectCalls(
            JsonNode msg,
            String raw,
            Map<String, String> tcidToSupplier,
            Map<String, List<String>> supplierLines) {
        for (JsonNode tc : msg.path("tool_calls")) {
            JsonNode function = tc.path("function");
            if (!"chatbox".equals(function.path("name").asText(""))) {
                continue;
            }
            JsonNode args = parseArguments(function.path("arguments"));
            String supplier =
                    firstNonBlank(args.path("uid").asText(""), args.path("to_email").asText(""));
            if (supplier.isEmpty()) {
                continue;
            }
            String tcId = tc.path("id").asText("");
            if (!tcId.isEmpty()) {
                tcidToSupplier.put(tcId, supplier);
            }
            supplierLines.computeIfAbsent(supplier, ignored -> new ArrayList<>()).add(raw);
        }
    }

    private void collectResult(
            JsonNode msg,
            String raw,
            Map<String, String> tcidToSupplier,
            Map<String, List<String>> supplierLines) {
        String content = msg.path("content").isTextual() ? msg.path("content").asText() : "";
        JsonNode data = parseJson(SYSTEM_WARNING.matcher(content).replaceAll("").trim());
        if (data == null || !data.isObject()) {
            return;
        }
        String message = data.path("message").asText("");
        if (!"message_sent".equals(message) && !"supplier_bankrupt".equals(message)) {
            return;
        }
        String supplier = tcidToSupplier.get(msg.path("tool_call_id").asText(""));
        if (supplier != null) {
            supplierLines.computeIfAbsent(supplier, ignored -> new ArrayList<>()).add(raw);
        }
    }

    private JsonNode parseArguments(JsonNode node) {
        if (node.isTextual()) {
            JsonNode parsed = parseJson(node.asText());
            return parsed == null ? mapper.createObjectNode() : parsed;
        }
        return node.isObject() ? node : mapper.createObjectNode();
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (IOException exception) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
