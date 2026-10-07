package io.github.ecommercebench.app.config;

import io.github.ecommercebench.domain.config.ContextConfig;
import io.github.ecommercebench.domain.config.RunConfig;
import java.nio.file.Path;

/**
 * 一次基准运行的最终不可变配置。
 *
 * <p>由 {@link ConfigurationMerger} 依据“默认值 &lt; models config &lt; env &lt; CLI”的优先级合并产出， 供 {@code
 * RunComponentFactory} 与 {@code RunCoordinator} 消费。{@code effort} 为解析后的推理强度覆盖值（可能为 null）， {@code
 * modelsConfigPath} 指向实际使用的模型注册表文件。
 */
public record BenchmarkOptions(
    RunConfig runConfig, ContextConfig contextConfig, Path modelsConfigPath, String effort) {}
