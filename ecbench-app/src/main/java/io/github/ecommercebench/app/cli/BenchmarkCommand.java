package io.github.ecommercebench.app.cli;

import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.ConfigurationMerger;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.app.run.RunCoordinator;
import io.github.ecommercebench.app.run.RunOutcome;
import io.github.ecommercebench.app.web.DashboardLauncher;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * 基准运行主命令（有 {@code --model} 时与 Python {@code run.py} 兼容），也是默认起 Web 服务的入口。
 *
 * <p>无 {@code --model}（或值为空白）时委托 {@link DashboardLauncher} 启动本地 dashboard 并阻塞，退出码语义同
 * {@code serve}。有 {@code --model} 时解析 CLI 参数、合并配置得到 {@link BenchmarkOptions}，再交由 {@link RunCoordinator}
 * 并行执行。退出码：0 全部成功，1 至少一个 run 失败，2 配置/参数错误（其余用法错误由 Picocli 直接返回非零）。
 */
@Command(
        name = "ecbench",
        mixinStandardHelpOptions = true,
        version = "ecbench 1.0",
        description = "E-Commerce Bench 运行器（无 --model 时启动 Web dashboard，有则与 Python run.py 兼容）",
        subcommands = {EvaluationCommand.class, ServeCommand.class})
public final class BenchmarkCommand implements Callable<Integer> {

    @Mixin
    private CliRunOptions options = new CliRunOptions();

    @Mixin
    private ServeOptions serve = new ServeOptions();

    private final RunComponentFactory factory;
    private final DashboardLauncher launcher;

    /** 供 Picocli 反射实例化的无参构造；运行组件工厂稍后由装配层注入，在此之前为 null。 */
    public BenchmarkCommand() {
        this(null);
    }

    /** 注入运行组件工厂，call 时交给 RunCoordinator 执行各 run。 */
    public BenchmarkCommand(RunComponentFactory factory) {
        this(factory, new DashboardLauncher());
    }

    /** 注入运行组件工厂与 dashboard 启动器；后者可被测试替换为记录型实现。 */
    public BenchmarkCommand(RunComponentFactory factory, DashboardLauncher launcher) {
        this.factory = factory;
        this.launcher = launcher;
    }

    /** 无 --model 时起 dashboard；否则校验并合并配置、并行执行全部 run，按结果打印并返回退出码（0 全成功、1 有失败、2 配置/装配错误）。 */
    @Override
    public Integer call() {
        if (options.model() == null || options.model().isBlank()) {
            return launcher.launch(
                    serve.host(),
                    serve.port(),
                    serve.catalog(),
                    options,
                    Path.of(System.getProperty("user.dir")),
                    options.logDir());
        }
        BenchmarkOptions merged;
        try {
            merged =
                    new ConfigurationMerger()
                            .merge(options, System.getenv(), Path.of(System.getProperty("user.dir")));
        } catch (RuntimeException exception) {
            System.err.println("配置错误: " + exception.getMessage());
            return 2;
        }
        if (factory == null) {
            System.err.println("运行组件工厂未装配，无法执行基准。");
            return 2;
        }
        List<RunOutcome> outcomes = new RunCoordinator(factory).run(merged);
        boolean anyFailed = false;
        for (RunOutcome outcome : outcomes) {
            if (outcome.succeeded()) {
                System.out.printf(
                        "Run %d 完成: %s, day=%d%n",
                        outcome.index(), outcome.result().canonicalReason(), outcome.result().finalDay());
            } else {
                anyFailed = true;
                System.out.printf("Run %d 失败: %s%n", outcome.index(), outcome.failure());
            }
        }
        return anyFailed ? 1 : 0;
    }

    public CliRunOptions options() {
        return options;
    }
}
