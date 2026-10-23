package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.ecommercebench.app.job.JobService;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * dashboard 的 HTTP 服务生命周期封装（JDK 内置 {@link HttpServer}，不引入 Web 框架）。
 *
 * <p>把 {@code /api} 交给 {@link ApiRouter}、其余路径交给 {@link StaticAssets}，固定小线程池并发处理请求。
 * 端口传 {@code 0} 时由系统分配，便于测试与端口占用回退。
 *
 * <p>运行任务<b>不跑在这个 HTTP 线程池上</b>：池只有 4 个线程，而一个 365 天 episode 是小时级任务，跑在上面会
 * 拖垮全部只读 API。任务由 {@link JobService} 自己的单线程执行器承载。
 */
public final class DashboardServer implements AutoCloseable {

    private static final int WORKER_THREADS = 4;

    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final JobService jobs;

    public DashboardServer(
            Path logDir,
            String host,
            int port,
            Map<String, RosterEntry> roster,
            ModelCatalogService models,
            JobService jobs)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        // 先绑定端口再建线程池与路由：绑定失败（如端口被占）时不会泄漏已创建的工作线程，
        // 且 ApiRouter 的 Origin 校验需要读实际绑定端口。
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS);
        this.jobs = jobs;
        ApiRouter api =
                new ApiRouter(
                        mapper,
                        new SessionScanner(logDir),
                        new CompareService(mapper),
                        new RunArtifactService(mapper, roster),
                        models,
                        jobs,
                        () -> httpServer.getAddress().getPort());
        this.httpServer.createContext("/api", api);
        this.httpServer.createContext("/", new StaticAssets());
        this.httpServer.setExecutor(this.executor);
    }

    /** 开始监听。 */
    public void start() {
        httpServer.start();
    }

    /** 实际绑定端口（请求 0 时为系统分配值）。 */
    public int port() {
        return httpServer.getAddress().getPort();
    }

    /** 立即停止监听、关闭工作线程池，并中断仍在跑的任务（不承诺优雅收尾）。 */
    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
        jobs.shutdown();
    }
}
