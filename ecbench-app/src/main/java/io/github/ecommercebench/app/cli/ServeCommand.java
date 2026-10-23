package io.github.ecommercebench.app.cli;

import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.web.DashboardLauncher;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 子命令 {@code serve}：启动本地 Web dashboard，浏览、对比 {@code log} 目录下的 run 产物，并可提交新的运行。
 *
 * <p>与根命令无 {@code --model} 时的行为完全一致，二者都委托 {@link DashboardLauncher}；保留本子命令只为显式表达
 * 意图与向后兼容既有脚本。进程阻塞直到收到中断（Ctrl-C），退出码 0；目录不存在或端口不可用返回 2。
 */
@Command(name = "serve", description = "启动本地 Web dashboard 浏览 / 对比 / 提交 run")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--log-dir", description = "会话根目录（每个子目录=一个会话）", defaultValue = "log")
    private Path logDir;

    @Mixin
    private ServeOptions serve = new ServeOptions();

    private final DashboardLauncher launcher;

    /** 供 Picocli 反射实例化的无参构造。 */
    public ServeCommand() {
        this(new DashboardLauncher());
    }

    /** 注入启动器，便于测试拦截。 */
    public ServeCommand(DashboardLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public Integer call() {
        return launcher.launch(
                serve.host(),
                serve.port(),
                serve.catalog(),
                serverDefaults(),
                Path.of(System.getProperty("user.dir")),
                logDir.toString());
    }

    /**
     * 把 serve 自己的 {@code --log-dir} 转成一份 {@link RunOptions}，作为 Web 提交任务的 4 个路径类字段来源。
     *
     * <p>复用 Picocli 解析而不是另写一个 13 方法的实现，可保证缺省值与 CLI 永远同源。
     */
    private RunOptions serverDefaults() {
        CliRunOptions defaults = new CliRunOptions();
        new CommandLine(defaults).parseArgs("--log-dir", logDir.toString());
        return defaults;
    }
}
