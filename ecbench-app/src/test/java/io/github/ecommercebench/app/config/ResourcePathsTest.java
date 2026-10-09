package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ResourcePaths 隔离解析测试：默认优先 {@code java-impl/} 副本，副本缺失时回退基准根；tokenizer 优先隔离副本目录，否则回退旧布局；CLI
 * 覆盖始终最高优先。
 */
class ResourcePathsTest {

    @Test
    void prefersIsolatedJavaImplCopyWhenPresent(@TempDir Path root) throws Exception {
        // 基准根含 data/（模拟仓库根 Python 侧），java-impl/ 下另有隔离副本
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("java-impl").resolve("data"));
        Files.createDirectories(root.resolve("java-impl").resolve("tokenizer"));

        ResourcePaths paths = ResourcePaths.of(root);
        Path isolated = root.resolve("java-impl");

        assertThat(paths.resourceRoot()).isEqualTo(isolated);
        assertThat(paths.dataDir(null)).isEqualTo(isolated.resolve("data"));
        assertThat(paths.modelsConfig(null)).isEqualTo(isolated.resolve("models_config.json"));
        assertThat(paths.tokenizer(null, null)).isEqualTo(isolated.resolve("tokenizer"));
    }

    @Test
    void fallsBackToBaseRootWhenNoIsolatedCopy(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("models_config.json"), "{}");
        Files.createDirectories(root.resolve("context_manager").resolve("tokenizer"));

        ResourcePaths paths = ResourcePaths.of(root);

        assertThat(paths.resourceRoot()).isEqualTo(root);
        assertThat(paths.dataDir(null)).isEqualTo(root.resolve("data"));
        assertThat(paths.modelsConfig(null)).isEqualTo(root.resolve("models_config.json"));
        assertThat(paths.tokenizer(null, null))
                .isEqualTo(root.resolve("context_manager").resolve("tokenizer"));
    }

    @Test
    void cliOverridesTakePrecedence(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("java-impl").resolve("data"));
        ResourcePaths paths = ResourcePaths.of(root);
        Path custom = root.resolve("custom").toAbsolutePath().normalize();

        assertThat(paths.dataDir(custom.toString())).isEqualTo(custom);
        assertThat(paths.tokenizer(custom.toString(), null)).isEqualTo(custom);
    }
}
