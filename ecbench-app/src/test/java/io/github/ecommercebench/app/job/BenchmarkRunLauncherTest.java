package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * 每次 launch 必须新建一个 RunComponentFactory，否则缓存的会话目录会串到下一个 job。
 *
 * <p>用同包的可覆盖接缝拦截，避免真跑仿真与加载 tokenizer。
 */
class BenchmarkRunLauncherTest {

    private static final LlmClientProvider NO_PROVIDER = config -> null;

    /** 拦截 factory 构造与编排，记录实例个数并让 launch 立刻返回。 */
    private static final class RecordingLauncher extends BenchmarkRunLauncher {
        private final List<RunComponentFactory> created = new ArrayList<>();
        private final List<Consumer<Path>> callbacks = new ArrayList<>();

        RecordingLauncher(ObjectMapper mapper, LlmClientProvider provider) {
            super(mapper, provider);
        }

        @Override
        RunComponentFactory newFactory(Consumer<Path> onSessionCreated) {
            RunComponentFactory factory = new RunComponentFactory(mapper(), provider(), onSessionCreated);
            created.add(factory);
            callbacks.add(onSessionCreated);
            return factory;
        }

        @Override
        List<RunOutcome> coordinate(RunComponentFactory factory, BenchmarkOptions options) {
            return List.of(new RunOutcome(0, null, null));
        }
    }

    @Test
    void eachLaunchBuildsItsOwnFactory() {
        RecordingLauncher launcher = new RecordingLauncher(new ObjectMapper(), NO_PROVIDER);

        launcher.launch(null, path -> {});
        launcher.launch(null, path -> {});

        assertThat(launcher.created).hasSize(2);
        assertThat(launcher.created.get(0)).isNotSameAs(launcher.created.get(1));
    }

    @Test
    void sessionCallbackIsForwardedToTheFactory() {
        RecordingLauncher launcher = new RecordingLauncher(new ObjectMapper(), NO_PROVIDER);
        List<Path> seen = new ArrayList<>();

        launcher.launch(null, seen::add);
        launcher.callbacks.get(0).accept(Path.of("/log/20261014_100001_gpt"));

        assertThat(seen).containsExactly(Path.of("/log/20261014_100001_gpt"));
    }

    @Test
    void launchReturnsTheCoordinatorOutcomes() {
        RecordingLauncher launcher = new RecordingLauncher(new ObjectMapper(), NO_PROVIDER);

        List<RunOutcome> outcomes = launcher.launch(null, path -> {});

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).index()).isZero();
    }
}
