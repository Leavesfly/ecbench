package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.ecommercebench.app.job.Job;
import io.github.ecommercebench.app.job.JobQueueFullException;
import io.github.ecommercebench.app.job.JobService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * dashboard JSON API 的路由：按 (方法, 路径) 分发，调用各 service，并把异常映射为状态码。
 *
 * <p>映射约定：非法会话名／非法 run／参数校验失败 → 400；跨域写请求 → 403；产物、会话与任务 id 缺失以及
 * 未知端点 → 404；已知路径但不支持该方法 → 405；队列已满与取消太晚 → 409；请求体超限 → 413；其余运行时
 * 异常 → 500（单请求失败不影响服务存活）。
 *
 * <p>{@code /api/compare} 未指定 {@code sessions} 时退化为对比全部已发现会话（上限截断，避免雷达过密）。
 */
public final class ApiRouter implements HttpHandler {

    /** 未显式选择会话时，一次对比的会话上限。 */
    private static final int MAX_AUTO_SESSIONS = 12;

    /** POST body 的字节上限，超出即 413。 */
    static final int MAX_BODY_BYTES = 65_536;

    private static final String PREFIX = "/api/sessions/";

    private static final String JOB_PREFIX = "/api/jobs/";

    private final ObjectMapper mapper;
    private final SessionScanner scanner;
    private final CompareService compare;
    private final RunArtifactService artifacts;
    private final ModelCatalogService models;
    private final JobService jobs;
    private final IntSupplier portSupplier;

    public ApiRouter(
            ObjectMapper mapper,
            SessionScanner scanner,
            CompareService compare,
            RunArtifactService artifacts,
            ModelCatalogService models,
            JobService jobs,
            IntSupplier portSupplier) {
        this.mapper = mapper;
        this.scanner = scanner;
        this.compare = compare;
        this.artifacts = artifacts;
        this.models = models;
        this.jobs = jobs;
        this.portSupplier = portSupplier;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, error(String.valueOf(exception.getMessage())));
        } catch (ArtifactNotFoundException exception) {
            write(exchange, 404, error(String.valueOf(exception.getMessage())));
        } catch (JobQueueFullException exception) {
            write(exchange, 409, error(String.valueOf(exception.getMessage())));
        } catch (ApiException exception) {
            write(exchange, exception.status(), error(String.valueOf(exception.getMessage())));
        } catch (RuntimeException exception) {
            write(exchange, 500, error("内部错误: " + exception.getClass().getSimpleName()));
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/sessions")) {
            requireGet(method, path);
            write(exchange, 200, sessionsNode());
            return;
        }
        if (path.equals("/api/compare")) {
            requireGet(method, path);
            write(exchange, 200, compareNode(query(exchange.getRequestURI())));
            return;
        }
        if (path.equals("/api/models")) {
            requireGet(method, path);
            write(exchange, 200, models.node());
            return;
        }
        if (path.equals("/api/jobs")) {
            if ("POST".equals(method)) {
                requireSameOrigin(exchange);
                Job job = jobs.submit(readBody(exchange));
                ObjectNode accepted = mapper.createObjectNode();
                accepted.put("id", job.id());
                accepted.put("state", job.state().name());
                write(exchange, 202, accepted);
                return;
            }
            requireGet(method, path);
            ObjectNode root = mapper.createObjectNode();
            root.set("jobs", jobs.jobsNode());
            write(exchange, 200, root);
            return;
        }
        if (path.startsWith(JOB_PREFIX)) {
            singleJob(exchange, method, path.substring(JOB_PREFIX.length()));
            return;
        }
        if (path.startsWith(PREFIX)) {
            requireGet(method, path);
            write(exchange, 200, runArtifact(path, query(exchange.getRequestURI())));
            return;
        }
        write(exchange, 404, error("未知端点: " + path));
    }

    /** {@code /api/jobs/{id}} 的 GET 与 DELETE；其余方法一律 405。 */
    private void singleJob(HttpExchange exchange, String method, String id) throws IOException {
        if (id.isEmpty() || id.contains("/")) {
            throw new IllegalArgumentException("非法任务 id: " + id);
        }
        if ("GET".equals(method)) {
            Optional<Job> found = jobs.find(id);
            if (found.isEmpty()) {
                throw new ArtifactNotFoundException("任务不存在: " + id);
            }
            write(exchange, 200, jobs.jobNode(found.get()));
            return;
        }
        if ("DELETE".equals(method)) {
            requireSameOrigin(exchange);
            switch (jobs.cancel(id)) {
                case NOT_FOUND -> throw new ArtifactNotFoundException("任务不存在: " + id);
                case ALREADY_FINISHED -> throw new ApiException(409, "任务已结束，无法取消");
                case CANCELLED -> {
                    Job job = jobs.find(id).orElseThrow();
                    ObjectNode node = mapper.createObjectNode();
                    node.put("id", job.id());
                    node.put("state", job.state().name());
                    write(exchange, 200, node);
                }
            }
            return;
        }
        requireGet(method, JOB_PREFIX + id);
    }

    /** 已知路径但不支持该方法 → 405（未知路径由 route 的兜底分支给 404，不进这里）。 */
    private static void requireGet(String method, String path) {
        if (!"GET".equals(method)) {
            throw new ApiException(405, "该端点仅支持 GET，收到 " + method + ": " + path);
        }
    }

    /**
     * 跨站防护：仅对写侧方法生效。
     *
     * <p>比对基准用<b>服务端已知的绑定端口</b>，不用请求的 {@code Host} 头（后者可被 DNS rebinding 篡改）。
     * 服务虽只绑 127.0.0.1，但浏览器里任意网页都能向它发 POST；不挡的话任何恶意页面都能拿用户的 API key
     * 跑几小时的任务。缺失 Origin 视为非浏览器客户端（curl），放行。
     */
    private void requireSameOrigin(HttpExchange exchange) {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        if (origins == null || origins.isEmpty()) {
            return;
        }
        String origin = origins.get(0);
        int port = portSupplier.getAsInt();
        if (origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port)) {
            return;
        }
        throw new ApiException(403, "跨域请求被拒绝");
    }

    /**
     * 读取并解析 POST body：边读边计数，超上限立即 413。
     *
     * <p>无论成功失败都要把请求体读干或关闭，否则 JDK HttpServer 会挂起该连接。解析失败的错误文案<b>不回显
     * 原始 body</b>，避免反射型内容注入。
     */
    private JsonNode readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    drain(in);
                    throw new ApiException(413, "请求体超过上限 " + MAX_BODY_BYTES + " 字节");
                }
                buffer.write(chunk, 0, read);
            }
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length == 0) {
            throw new IllegalArgumentException("请求体为空");
        }
        try {
            return mapper.readTree(bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("请求体不是合法 JSON");
        }
    }

    /** 丢弃剩余请求体，让连接可以被复用而不是挂起。 */
    private static void drain(InputStream in) {
        try {
            byte[] sink = new byte[8192];
            while (in.read(sink) != -1) {
                // 逐块丢弃
            }
        } catch (IOException ignored) {
            // 读干失败无妨：连接随后会被服务端关闭
        }
    }

    private JsonNode runArtifact(String path, Map<String, List<String>> query) {
        String remainder = path.substring(PREFIX.length());
        int separator = remainder.lastIndexOf('/');
        if (separator <= 0 || separator == remainder.length() - 1) {
            throw new IllegalArgumentException("路径需要 {name}/{kind}");
        }
        String name = remainder.substring(0, separator);
        String kind = remainder.substring(separator + 1);
        int run = parseRun(first(query.get("run"), "0"));
        Path dir = scanner.sessionDir(name);
        return switch (kind) {
            case "analysis" -> artifacts.analysis(dir, run);
            case "balance" -> artifacts.balance(dir, run);
            case "chatbox" -> artifacts.chatbox(dir, run);
            default -> throw new IllegalArgumentException("未知产物类型: " + kind);
        };
    }

    private ArrayNode sessionsNode() {
        ArrayNode array = mapper.createArrayNode();
        for (SessionInfo info : scanner.scan()) {
            array.addObject()
                    .put("name", info.name())
                    .put("modelKey", info.modelKey())
                    .put("runs", info.runCount())
                    .put("hasBalance", info.hasBalance());
        }
        return array;
    }

    private JsonNode compareNode(Map<String, List<String>> query) {
        List<String> names = query.getOrDefault("sessions", List.of());
        List<Path> dirs = new ArrayList<>();
        for (String name : names) {
            dirs.add(scanner.sessionDir(name));
        }
        if (dirs.isEmpty()) {
            scanner.scan().stream()
                    .limit(MAX_AUTO_SESSIONS)
                    .forEach(info -> dirs.add(scanner.sessionDir(info.name())));
        }
        return compare.compare(dirs);
    }

    private static int parseRun(String value) {
        if (!value.matches("\\d+")) {
            throw new IllegalArgumentException("非法 run 序号: " + value);
        }
        return Integer.parseInt(value);
    }

    private static String first(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static Map<String, List<String>> query(URI uri) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return map;
    }

    private JsonNode error(String message) {
        return mapper.createObjectNode().put("error", message);
    }

    private void write(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
