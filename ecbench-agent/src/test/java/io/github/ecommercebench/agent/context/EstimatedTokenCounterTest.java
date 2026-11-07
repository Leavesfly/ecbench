package io.github.ecommercebench.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.llm.model.ToolCall;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * EstimatedTokenCounter 的字符口径与消息聚合测试。
 */
class EstimatedTokenCounterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EstimatedTokenCounter counter = new EstimatedTokenCounter();

    @Test
    void countsCjkCharactersOneToOne() {
        assertThat(counter.count("你好世界")).isEqualTo(4);
    }

    @Test
    void countsLatinTextByCharsPerToken() {
        assertThat(counter.count("abcdefg")).isEqualTo(2);
    }

    @Test
    void countsMixedTextByBothRules() {
        // 两个汉字计 2，余下 6 个 ASCII 字符按 ceil(6/3.5) 计 2。
        assertThat(counter.count("你好world!")).isEqualTo(4);
    }

    @Test
    void nullAndEmptyCountZero() {
        assertThat(counter.count((String) null)).isZero();
        assertThat(counter.count("")).isZero();
    }

    @Test
    void aggregatesContentReasoningAndToolCalls() throws Exception {
        ToolCall call = new ToolCall("c1", "abc", MAPPER.readTree("{\"a\":1}"), null);
        ChatMessage message = ChatMessage.assistant("你好", List.of(call), "abcd", List.of());

        // 正文 2 + 推理 ceil(4/3.5)=2 + 工具名 ceil(3/3.5)=1 + 参数 ceil(7/3.5)=2。
        assertThat(counter.count(message)).isEqualTo(7);
    }

    @Test
    void clearedMessageCountsZero() {
        ChatMessage message = ChatMessage.tool("c1", "很长的工具响应").markCleared();

        assertThat(counter.count(message)).isZero();
    }
}
