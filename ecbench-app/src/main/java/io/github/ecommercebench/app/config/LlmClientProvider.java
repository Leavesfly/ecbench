package io.github.ecommercebench.app.config;

import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.llm.LlmClient;

/**
 * 创建 {@link LlmClient} 的可注入函数式接缝。
 *
 * <p>生产环境由 {@code LlmClientFactory} 适配（按 provider 协议构建真实客户端）；测试可提供离线假实现，从而在不触网、不需凭据的情况下装配 完整 run
 * 组件。这是 {@code RunComponentFactory} 与具体 LLM 传输解耦的唯一入口。
 */
@FunctionalInterface
public interface LlmClientProvider {

    LlmClient create(ModelConfig config);
}
