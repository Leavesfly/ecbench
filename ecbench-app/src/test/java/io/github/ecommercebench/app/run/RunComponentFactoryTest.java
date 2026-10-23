package io.github.ecommercebench.app.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.ConfigurationMerger;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.log.RunDirectory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话目录创建回调契约：目录建好后触发一次，且既有两参构造行为不变。 */
class RunComponentFactoryTest {

    private static final String CONFIG_JSON =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {"gpt": {"provider": "openai", "model_name": "gpt-x"}}
                    }
                    """;

    private static final LlmClientProvider NO_PROVIDER = config -> null;

    /** 只填 model 与 logDir 的最小 RunOptions，避免触发 catalog / tokenizer 加载。 */
    private record MinimalOptions(String model, String logDir) implements RunOptions {
        @Override
        public int maxTokens() {
            return 16384;
        }

        @Override
        public int maxTurns() {
            return 4000;
        }

        @Override
        public int maxDays() {
            return 365;
        }

        @Override
        public double initialBalance() {
            return 100000.0;
        }

        @Override
        public double dailyFee() {
            return 50.0;
        }

        @Override
        public int maxTokenCapacity() {
            return 128000;
        }

        @Override
        public String tokenizerPath() {
            return null;
        }

        @Override
        public String jobFile() {
            return null;
        }

        @Override
        public int runs() {
            return 1;
        }

        @Override
        public Long seed() {
            return null;
        }

        @Override
        public String dataDir() {
            return null;
        }
    }

    /** @TempDir 不能注入到静态辅助方法，故工作目录与日志目录都显式传参。 */
    private static BenchmarkOptions optionsFor(Path workingDir, Path logDir) throws Exception {
        Files.writeString(workingDir.resolve("models_config.json"), CONFIG_JSON);
        return new ConfigurationMerger().merge(new MinimalOptions("gpt", logDir.toString()), Map.of(), workingDir);
    }

    @Test
    void notifiesSessionDirectoryOnceCreated(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        List<Path> seen = new ArrayList<>();
        RunComponentFactory factory = new RunComponentFactory(new ObjectMapper(), NO_PROVIDER, seen::add);

        RunDirectory directory = factory.sharedRunDirectory(optionsFor(workingDir, logDir));

        assertThat(seen).containsExactly(directory.sessionDir());
        assertThat(Files.isDirectory(directory.sessionDir())).isTrue();
        assertThat(directory.sessionDir().getFileName().toString()).endsWith("_gpt");
        assertThat(directory.sessionDir().getParent()).isEqualTo(logDir);
    }

    @Test
    void secondCallReusesCachedDirectoryAndDoesNotNotifyAgain(
            @TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        List<Path> seen = new ArrayList<>();
        RunComponentFactory factory = new RunComponentFactory(new ObjectMapper(), NO_PROVIDER, seen::add);
        BenchmarkOptions options = optionsFor(workingDir, logDir);

        RunDirectory first = factory.sharedRunDirectory(options);
        RunDirectory second = factory.sharedRunDirectory(options);

        assertThat(second).isSameAs(first);
        assertThat(seen).hasSize(1);
    }

    @Test
    void twoArgConstructorStillCreatesTheLayoutAndNotifiesNobody(
            @TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        RunComponentFactory factory = new RunComponentFactory(new ObjectMapper(), NO_PROVIDER);

        RunDirectory directory = factory.sharedRunDirectory(optionsFor(workingDir, logDir));

        assertThat(Files.isDirectory(directory.trajectoriesDir())).isTrue();
        assertThat(Files.isDirectory(directory.metricsDir())).isTrue();
        assertThat(Files.isDirectory(directory.balanceDir())).isTrue();
    }

    @Test
    void twoFactoriesCreateTwoDistinctSessionDirectories(
            @TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        BenchmarkOptions options = optionsFor(workingDir, logDir);

        Path first = new RunComponentFactory(new ObjectMapper(), NO_PROVIDER)
                .sharedRunDirectory(options)
                .sessionDir();
        Path second = new RunComponentFactory(new ObjectMapper(), NO_PROVIDER)
                .sharedRunDirectory(options)
                .sessionDir();

        assertThat(first).isNotEqualTo(second);
    }
}
