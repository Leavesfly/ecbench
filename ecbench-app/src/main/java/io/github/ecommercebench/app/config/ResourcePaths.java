package io.github.ecommercebench.app.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Java 实现资源路径解析器。
 *
 * <p>{@code java-impl/} 持有自己的隔离副本（{@code data/}、{@code models_config.json}、{@code
 * tokenizer/tokenizer.json}），与仓库根 Python 侧共享资源解耦。本类默认解析到该副本（见 {@link #resourceRoot()}）， 并允许 CLI/env
 * 显式覆盖每一处路径；副本缺失时回退到仓库根的 {@code data/}、{@code models_config.json}、{@code
 * context_manager/tokenizer/}。
 */
public final class ResourcePaths {

    private final Path workingDir;

    private ResourcePaths(Path workingDir) {
        this.workingDir = workingDir.toAbsolutePath().normalize();
    }

    public static ResourcePaths of(Path workingDir) {
        return new ResourcePaths(workingDir);
    }

    public Path workingDir() {
        return workingDir;
    }

    /**
     * 从工作目录向上查找基准根（含 {@code models_config.json} 或 {@code data} 子目录）；找不到时回退到工作目录本身。
     */
    public Path repoRoot() {
        Path dir = workingDir;
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("models_config.json"))
                    || Files.isDirectory(dir.resolve("data"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return workingDir;
    }

    /**
     * Java 实现的资源根：优先 {@code java-impl/} 内的隔离副本，与仓库根 Python 侧资源解耦。
     *
     * <p>先向上探测基准根（见 {@link #repoRoot()}）；若基准根下存在 {@code java-impl/data} 目录，则资源根为 {@code
     * java-impl}，否则回退基准根。该规则在三种情形下均确定性命中副本：从仓库根运行 jar、从 {@code java-impl} 内运行测试、 以及 {@code java-impl}
     * 被单独抽出（此时基准根即其自身，无同名嵌套子目录故回退）。
     */
    public Path resourceRoot() {
        Path base = repoRoot();
        Path isolated = base.resolve("java-impl");
        if (Files.isDirectory(isolated.resolve("data"))) {
            return isolated;
        }
        return base;
    }

    /**
     * 模型注册表：env 覆盖 &gt; 资源根 {@code models_config.local.json} &gt; 资源根 {@code models_config.json}。
     */
    public Path modelsConfig(String envOverride) {
        if (isSet(envOverride)) {
            return absolute(envOverride);
        }
        Path root = resourceRoot();
        Path local = root.resolve("models_config.local.json");
        if (Files.isRegularFile(local)) {
            return local;
        }
        return root.resolve("models_config.json");
    }

    /**
     * 数据目录：CLI 覆盖 &gt; 资源根 {@code data/}。
     */
    public Path dataDir(String cliOverride) {
        return isSet(cliOverride) ? absolute(cliOverride) : resourceRoot().resolve("data");
    }

    /**
     * tokenizer 目录：CLI 覆盖 &gt; env 覆盖 &gt; 资源根 {@code tokenizer/}（隔离副本）&gt; 资源根 {@code
     * context_manager/tokenizer}（仓库根旧布局回退）。
     */
    public Path tokenizer(String cliOverride, String envOverride) {
        if (isSet(cliOverride)) {
            return absolute(cliOverride);
        }
        if (isSet(envOverride)) {
            return absolute(envOverride);
        }
        Path root = resourceRoot();
        Path isolated = root.resolve("tokenizer");
        if (Files.isDirectory(isolated)) {
            return isolated;
        }
        return root.resolve("context_manager").resolve("tokenizer");
    }

    /**
     * 可选路径：仅在提供覆盖值时返回绝对路径，否则返回 null。
     */
    public Path optional(String override) {
        return isSet(override) ? absolute(override) : null;
    }

    private static Path absolute(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
