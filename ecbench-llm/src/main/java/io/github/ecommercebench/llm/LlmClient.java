package io.github.ecommercebench.llm;

import io.github.ecommercebench.llm.model.LlmRequest;
import io.github.ecommercebench.llm.model.LlmResponse;

/** 多模型 Provider 的统一同步调用接口。 */
@FunctionalInterface
public interface LlmClient {
  LlmResponse generate(LlmRequest request);
}
