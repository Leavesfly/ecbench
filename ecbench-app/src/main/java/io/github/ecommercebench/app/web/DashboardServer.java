package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * dashboard 的 HTTP 服务生命周期封装（JDK 内置 {@link HttpServer}，不引入 Web 框架）。
 *
 * <p>把 {@code /api} 交给 {@link ApiRouter}、其余路径交给 {@link StaticAssets}，固定小线程池并发处理只读请求。
 * 端口传 {@code 0} 时由系统分配，便于测试与端口占用回退。
 */
public final class DashboardServer implements AutoCloseable {

    private static final int WORKER_THREADS = 4;

    private final HttpServer httpServer;
    private final ExecutorService executor;

    public DashboardServer(Path logDir, String host, int port) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ApiRouter api =
                new ApiRouter(
                        mapper,
                        new SessionScanner(logDir),
                        new CompareService(mapper),
                        new RunArtifactService(mapper));
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS);
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
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

    /** 立即停止监听并关闭工作线程池。 */
    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
    }
}
