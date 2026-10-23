package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.app.run.RunCoordinator;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link RunLauncher} 的生产实现。
 *
 * <p><b>每次 launch 都新建一个 {@link RunComponentFactory}</b>。这不是浪费：该工厂把 catalog、models 注册表、
 * tokenizer、尤其是<b>会话目录</b>缓存成实例字段且「第一个 options 胜出」，复用一个实例跑第二个 job 会让产物写进第一个
 * job 的会话目录，并静默沿用第一个 job 的 data-dir / models_config / tokenizer 路径。
 *
 * <p>相较之下 {@link LlmClientProvider} 是无状态且线程安全的（既有 {@code RunCoordinator} 已并发调用同一实例的
 * {@code create}），因此跨 job 复用，不重建。
 *
 * <p>{@code newFactory} 与 {@code coordinate} 为包级可覆盖接缝，使测试无需真跑仿真即可验证「不串味」这条不变量。
 */
public class BenchmarkRunLauncher implements RunLauncher {

    private final ObjectMapper mapper;
    private final LlmClientProvider provider;

    public BenchmarkRunLauncher(ObjectMapper mapper, LlmClientProvider provider) {
        this.mapper = mapper;
        this.provider = provider;
    }

    @Override
    public List<RunOutcome> launch(BenchmarkOptions options, Consumer<Path> onSessionCreated) {
        return coordinate(newFactory(onSessionCreated), options);
    }

    /** 供同包测试拦截：生产实现新建一个携带会话回调的工厂。 */
    RunComponentFactory newFactory(Consumer<Path> onSessionCreated) {
        return new RunComponentFactory(mapper, provider, onSessionCreated);
    }

    /** 供同包测试拦截：生产实现走既有的并行编排器。 */
    List<RunOutcome> coordinate(RunComponentFactory factory, BenchmarkOptions options) {
        return new RunCoordinator(factory).run(options);
    }

    ObjectMapper mapper() {
        return mapper;
    }

    LlmClientProvider provider() {
        return provider;
    }
}
