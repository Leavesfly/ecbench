package io.github.ecommercebench.evaluation.model;

import java.util.List;

/**
 * 一个供应商的 chatbox 会话，端口 Python {@code extract_chatbox} 的按 supplier 分组结果。
 *
 * <p>{@code rawLines} 保留原始 JSONL 行（逐字，含 reasoning_content/content/tool_calls），按消息出现顺序排列， 与 Python
 * 输出 {@code <supplier>.jsonl} 的内容一致。
 */
public record ChatboxConversation(String supplier, List<String> rawLines) {

    public ChatboxConversation {
        rawLines = List.copyOf(rawLines);
    }

    public int messageCount() {
        return rawLines.size();
    }
}
