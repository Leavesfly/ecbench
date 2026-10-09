package io.github.ecommercebench.agent.context;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.github.ecommercebench.llm.model.ChatMessage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 使用项目原有 tokenizer.json 进行精确 token 计数。
 */
public final class HuggingFaceTokenCounter implements TokenCounter, AutoCloseable {

    private final HuggingFaceTokenizer tokenizer;

    private HuggingFaceTokenCounter(HuggingFaceTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public static HuggingFaceTokenCounter load(Path tokenizerJson) {
        try {
            return new HuggingFaceTokenCounter(HuggingFaceTokenizer.newInstance(tokenizerJson));
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法加载 tokenizer: " + tokenizerJson, exception);
        }
    }

    public int count(String text) {
        return text == null || text.isEmpty() ? 0 : tokenizer.encode(text).getIds().length;
    }

    @Override
    public int count(ChatMessage message) {
        int total = count(message.content()) + count(message.reasoningContent());
        for (var call : message.toolCalls()) {
            total += count(call.name()) + count(call.arguments().toString());
        }
        return message.cleared() ? 0 : total;
    }

    @Override
    public void close() {
        tokenizer.close();
    }
}
