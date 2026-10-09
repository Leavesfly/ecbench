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

    /** 私有构造；请经由 {@link #load(Path)} 从 tokenizer.json 构建。 */
    private HuggingFaceTokenCounter(HuggingFaceTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /** 从指定 tokenizer.json 加载分词器；加载失败（IO）包装为 IllegalArgumentException。 */
    public static HuggingFaceTokenCounter load(Path tokenizerJson) {
        try {
            return new HuggingFaceTokenCounter(HuggingFaceTokenizer.newInstance(tokenizerJson));
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法加载 tokenizer: " + tokenizerJson, exception);
        }
    }

    /** 统计原始文本的 token 数；空或空串记 0。 */
    public int count(String text) {
        return text == null || text.isEmpty() ? 0 : tokenizer.encode(text).getIds().length;
    }

    /** 累加正文、推理内容与各工具调用(name+arguments)的 token；已清除消息计 0。 */
    @Override
    public int count(ChatMessage message) {
        int total = count(message.content()) + count(message.reasoningContent());
        for (var call : message.toolCalls()) {
            total += count(call.name()) + count(call.arguments().toString());
        }
        return message.cleared() ? 0 : total;
    }

    /** 关闭底层 tokenizer，释放本地资源。 */
    @Override
    public void close() {
        tokenizer.close();
    }
}
