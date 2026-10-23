package io.github.ecommercebench.app.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 进程可见的唯一密钥视图：真实环境变量覆盖本地 {@code .env} 文件。
 *
 * <p>优先级刻意定为「env 胜出、文件只填补缺失项」，避免用户 {@code export} 的值被文件静默覆盖。该视图被三处共用——
 * {@code ApplicationWiring.llmClientProvider()}、{@code ConfigurationMerger} 的 env 入参、以及 {@code /api/models}
 * 的 {@code keyReady} 判定——从而杜绝「表单说就绪、真跑却缺 key」的漂移。
 */
public final class EffectiveEnvironment {

    private final Map<String, String> merged;

    /** 生产入口：读进程环境变量与仓库根 {@code .env}。 */
    private EffectiveEnvironment() {
        this(System.getenv(), new SecretsFileLoader().read(defaultFile(currentWorkingDir())));
    }

    /** 包级构造：注入两份来源，便于测试。 */
    EffectiveEnvironment(Map<String, String> processEnv, Map<String, String> fileEnv) {
        Map<String, String> result = new HashMap<>(fileEnv);
        result.putAll(processEnv);
        this.merged = Collections.unmodifiableMap(result);
    }

    /** 加载进程环境变量与默认密钥文件。 */
    public static EffectiveEnvironment load() {
        return new EffectiveEnvironment();
    }

    /** 合并后的不可变视图。 */
    public Map<String, String> asMap() {
        return merged;
    }

    /** 密钥文件默认位置：基准根下的 {@code .env}（基准根探测复用 {@link ResourcePaths#repoRoot()}）。 */
    public static Path defaultFile(Path workingDir) {
        return ResourcePaths.of(workingDir).repoRoot().resolve(".env");
    }

    private static Path currentWorkingDir() {
        return Path.of(System.getProperty("user.dir"));
    }
}
