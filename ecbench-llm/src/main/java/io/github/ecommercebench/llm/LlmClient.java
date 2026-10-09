package io.github.ecommercebench.llm;

import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;

/**
 * 多模型 Provider 的统一同步调用接口。
 */
@FunctionalInterface
public interface LlmClient {
    /** 同步发起一次对话补全；将工具定义与多轮消息交给具体 Provider，失败时抛出 ProviderException。 */
    LlmResponse generate(LlmRequest request);
}
