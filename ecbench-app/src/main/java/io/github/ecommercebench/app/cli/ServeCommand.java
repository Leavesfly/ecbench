package io.github.ecommercebench.app.cli;

import io.github.ecommercebench.app.web.DashboardServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 子命令 {@code serve}：启动本地只读 Web dashboard，浏览并对比 {@code log} 目录下的 run 产物。
 *
 * <p>与批处理运行不同，本命令是唯一会绑定端口的入口（默认仅监听本地回环），从而保留项目「默认无 Web」的设计；
 * 进程阻塞直到收到中断（Ctrl-C），退出码 0；目录不存在或端口不可用返回 2。
 */
@Command(name = "serve", description = "启动本地 Web dashboard 浏览 / 对比 run 产物")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--log-dir", description = "会话根目录（每个子目录=一个会话）", defaultValue = "log")
    private Path logDir;

    @Option(names = "--port", description = "监听端口，0 表示随机分配", defaultValue = "8080")
    private int port;

    @Option(names = "--host", description = "绑定地址，默认仅本地回环", defaultValue = "127.0.0.1")
    private String host;

    @Override
    public Integer call() {
        Path root = resolveLogDir();
        if (!Files.isDirectory(root)) {
            System.err.println("日志目录不存在: " + root);
            return 2;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        try (DashboardServer server = new DashboardServer(root, host, port)) {
            server.start();
            System.out.printf("Dashboard: http://%s:%d/ （log-dir=%s）%n", host, server.port(), root);
            awaitShutdown(stopped);
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IOException exception) {
            System.err.println("启动失败: " + exception.getMessage());
            return 2;
        }
    }

    /** 相对路径按当前工作目录解析，与基准运行的产物目录约定一致。 */
    private Path resolveLogDir() {
        Path base = Path.of(System.getProperty("user.dir"));
        return (logDir.isAbsolute() ? logDir : base.resolve(logDir)).normalize();
    }

    /** 注册关闭钩子并阻塞，直到进程被中断。 */
    private void awaitShutdown(CountDownLatch stopped) throws InterruptedException {
        Thread hook = new Thread(stopped::countDown);
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM 正在关闭：无需再注册钩子，直接等待。
        }
        try {
            stopped.await();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // 关闭流程中移除钩子会抛此异常，忽略即可。
            }
        }
    }
}
