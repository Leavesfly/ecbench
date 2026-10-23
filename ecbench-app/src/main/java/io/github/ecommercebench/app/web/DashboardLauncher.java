package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.EffectiveEnvironment;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.config.LlmClientProviders;
import io.github.ecommercebench.app.config.ResourcePaths;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.job.BenchmarkRunLauncher;
import io.github.ecommercebench.app.job.JobService;
import io.github.ecommercebench.app.job.ModelKeyGuard;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 起 Web 服务的共用编排，被 {@code serve} 子命令与根命令的无 {@code --model} 分支共同委托。
 *
 * <p>与批处理运行不同，这是唯一会绑定端口的路径（默认仅监听本地回环）。进程阻塞直到收到中断，退出码 0；日志目录
 * 不存在或端口不可用返回 2。
 *
 * <p>密钥与 provider 的构造<b>全部延后到 {@link #launch} 内部</b>，因此 {@code new DashboardLauncher()} 本身不触发
 * 任何文件读取或 stderr 输出——这让 {@code BenchmarkCommand} 的无参构造在测试里保持安静。
 *
 * <p>{@code start} 为包级可覆盖接缝，使同包测试无需真绑端口即可验证前置校验与参数透传；{@code app.cli} 包的测试
 * 看不到包级成员，只能覆盖 public 的 {@code launch}。
 */
public class DashboardLauncher {

    /**
     * 校验目录、装配服务、阻塞至中断。
     *
     * @param logDirOverride 会话根目录覆盖值，null 表示 {@code workingDir/log}
     * @return 退出码：正常中断 0，目录不存在或启动失败 2
     */
    public int launch(
            String host,
            int port,
            String catalog,
            RunOptions serverDefaults,
            Path workingDir,
            String logDirOverride) {
        Path root = resolveLogDir(logDirOverride, workingDir);
        if (!Files.isDirectory(root)) {
            System.err.println("日志目录不存在: " + root);
            return 2;
        }
        return start(host, port, catalog, serverDefaults, root, workingDir);
    }

    /** 真正装配并阻塞的部分；包级可覆盖以便同包测试拦截。 */
    int start(
            String host,
            int port,
            String catalog,
            RunOptions serverDefaults,
            Path root,
            Path workingDir) {
        ObjectMapper mapper = new ObjectMapper();
        EffectiveEnvironment environment = EffectiveEnvironment.load();
        Map<String, String> env = environment.asMap();
        ResourcePaths paths = ResourcePaths.of(workingDir);
        Map<String, RosterEntry> roster =
                new SupplierRosterReader().readByEmail(resolveCatalog(catalog, workingDir));
        ModelKeyGuard keys =
                new ModelKeyGuard(paths.modelsConfig(env.get("ECBENCH_MODELS_CONFIG")), env, mapper);
        LlmClientProvider provider = LlmClientProviders.fromEnvironment(env, mapper);
        JobService jobs =
                new JobService(
                        new BenchmarkRunLauncher(mapper, provider), keys, serverDefaults, env, workingDir, mapper);
        CountDownLatch stopped = new CountDownLatch(1);
        try (DashboardServer server =
                new DashboardServer(root, host, port, roster, new ModelCatalogService(keys, mapper), jobs)) {
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

    /** 名册路径解析：{@code --catalog} 覆盖优先，否则回退到资源根 {@code data/suppliers.csv}。 */
    public static Path resolveCatalog(String override, Path workingDir) {
        ResourcePaths paths = ResourcePaths.of(workingDir);
        Path custom = paths.optional(override);
        return custom != null ? custom : paths.dataDir(null).resolve("suppliers.csv");
    }

    /**
     * 会话根解析：覆盖值优先，否则 {@code workingDir/log}。
     *
     * <p>相对覆盖值按传入的 {@code workingDir} 解析，而**不是**按 JVM 当前工作目录——两者在生产里相同，但一旦不同
     * （如测试传入临时目录）就会静默解析到错的地方。此处不能用 {@code ResourcePaths.optional}，它固定按
     * {@code user.dir} 转绝对路径。
     */
    public static Path resolveLogDir(String override, Path workingDir) {
        if (override == null || override.isBlank()) {
            return workingDir.resolve("log").toAbsolutePath().normalize();
        }
        Path custom = Path.of(override);
        return (custom.isAbsolute() ? custom : workingDir.resolve(custom)).normalize();
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
