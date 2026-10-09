package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.model.ChatboxConversation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ChatboxExtractor 测试：用 tool_call_id 匹配 chatbox 调用/结果并按 supplier 分组，忽略非 chatbox 与未匹配结果。
 */
class ChatboxExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String assistantChatbox(String id, String uid) throws Exception {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "assistant").put("content", "");
        ObjectNode call = msg.putArray("tool_calls").addObject();
        call.put("id", id);
        ObjectNode function = call.putObject("function");
        function.put("name", "chatbox");
        function.put("arguments", MAPPER.writeValueAsString(Map.of("uid", uid, "content", "hi")));
        return msg.toString();
    }

    private static String toolResult(String id, String message) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "tool");
        msg.put("content", "{\"message\":\"" + message + "\",\"supplier_reply\":\"ok\"}");
        msg.put("tool_call_id", id);
        return msg.toString();
    }

    @Test
    void groupsChatboxCallsAndResultsBySupplier(@TempDir Path tempDir) throws Exception {
        Path jsonl = tempDir.resolve("run_0_messages.jsonl");
        List<String> lines = new ArrayList<>();
        lines.add(assistantChatbox("t1", "a@x.com"));
        lines.add(toolResult("t1", "message_sent"));
        lines.add(assistantChatbox("t2", "b@y.com"));
        lines.add(toolResult("t2", "message_sent"));
        lines.add(assistantChatbox("t3", "a@x.com"));
        lines.add(toolResult("t3", "supplier_bankrupt"));
        Files.write(jsonl, lines);

        Map<String, ChatboxConversation> conversations = new ChatboxExtractor().extract(jsonl);

        assertThat(conversations).containsKeys("a@x.com", "b@y.com");
        assertThat(conversations.get("a@x.com").messageCount()).isEqualTo(4);
        assertThat(conversations.get("b@y.com").messageCount()).isEqualTo(2);
    }

    @Test
    void ignoresNonChatboxCallsAndUnmatchedResults(@TempDir Path tempDir) throws Exception {
        Path jsonl = tempDir.resolve("run_0_messages.jsonl");
        ObjectNode other = MAPPER.createObjectNode();
        other.put("role", "assistant").put("content", "");
        ObjectNode call = other.putArray("tool_calls").addObject();
        call.put("id", "z1");
        call.putObject("function").put("name", "check_balance").put("arguments", "{}");

        List<String> lines = new ArrayList<>();
        lines.add(other.toString());
        lines.add(toolResult("unknown_id", "message_sent"));
        Files.write(jsonl, lines);

        assertThat(new ChatboxExtractor().extract(jsonl)).isEmpty();
    }
}
