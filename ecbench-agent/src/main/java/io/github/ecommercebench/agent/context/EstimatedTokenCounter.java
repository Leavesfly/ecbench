package io.github.ecommercebench.agent.context;

import io.github.ecommercebench.llm.model.ChatMessage;

/**
 * 基于字符类别的启发式 token 估算器，取代原先依赖 HuggingFace 分词器文件的精确计数。
 *
 * <p>CJK 汉字、假名、韩文与中文全角标点按「1 字符 ≈ 1 token」计，其余字符按 3.5 字符/token 折算。该口径对中文语料整体
 * 略偏高，方向是安全的：宁可提前裁剪，也不低估水位去撞 Provider 的上下文超限错误（4xx 不可重试，会直接终止 episode）。
 */
public final class EstimatedTokenCounter implements TokenCounter {

    /** 非 CJK 字符平均每个 token 承载的字符数（英文与 JSON 结构的经验值）。 */
    private static final double CHARS_PER_TOKEN = 3.5;

    /**
     * 统计原始文本的估算 token 数；null 或空串记 0。
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
            index += Character.charCount(codePoint);
        }
        return cjk + (int) Math.ceil(other / CHARS_PER_TOKEN);
    }

    /**
     * 累加正文、推理内容与各工具调用(name+arguments)的估算 token；已清除消息计 0。
     */
    @Override
    public int count(ChatMessage message) {
        int total = count(message.content()) + count(message.reasoningContent());
        for (var call : message.toolCalls()) {
            total += count(call.name()) + count(call.arguments().toString());
        }
        return message.cleared() ? 0 : total;
    }

    /**
     * 判断是否为按 1 字符 ≈ 1 token 计的 CJK 字符或其全角标点。
     */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x2E80 && codePoint <= 0x9FFF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }
}
