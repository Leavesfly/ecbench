package io.github.ecommercebench.app.job;

import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 执行一次基准运行的接缝。
 *
 * <p>生产实现是 {@link BenchmarkRunLauncher}；测试用假实现驱动 {@code JobService} 的整套状态机与取消逻辑，
 * 完全不碰 LLM 与仿真引擎。
 *
 * <p>{@code onSessionCreated} 必须透传给 {@code RunComponentFactory} 的三参构造，以便会话目录一建好就把名字回填给
 * 任务，前端才能在运行中（而不是结束后）看到可点击的会话名。
 */
@FunctionalInterface
public interface RunLauncher {

    /**
     * 跑完 options 指定的全部 run。
     *
     * @param options 已合并的运行配置
     * @param onSessionCreated 会话目录创建后回调一次，参数为该目录
     * @return 各 run 的结果，按 index 升序
     */
    List<RunOutcome> launch(BenchmarkOptions options, Consumer<Path> onSessionCreated);
}
