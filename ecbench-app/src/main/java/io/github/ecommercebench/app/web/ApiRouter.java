package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dashboard 只读 JSON API 的路由：解析路径与查询串，调用各 service，并把异常映射为状态码。
 *
 * <p>映射约定：非法会话名／非法 run → 400；产物缺失与未知端点 → 404；非 GET → 405；其余运行时异常 → 500
 * （单请求失败不影响服务存活）。{@code /api/compare} 未指定 {@code sessions} 时退化为对比全部已发现会话（上限截断，避免雷达过密）。
 */
public final class ApiRouter implements HttpHandler {

    /** 未显式选择会话时，一次对比的会话上限。 */
    private static final int MAX_AUTO_SESSIONS = 12;

    private static final String PREFIX = "/api/sessions/";

    private final ObjectMapper mapper;
    private final SessionScanner scanner;
    private final CompareService compare;
    private final RunArtifactService artifacts;

    public ApiRouter(
            ObjectMapper mapper,
            SessionScanner scanner,
            CompareService compare,
            RunArtifactService artifacts) {
        this.mapper = mapper;
        this.scanner = scanner;
        this.compare = compare;
        this.artifacts = artifacts;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                write(exchange, 405, error("仅支持 GET"));
                return;
            }
            route(exchange);
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, error(String.valueOf(exception.getMessage())));
        } catch (ArtifactNotFoundException exception) {
            write(exchange, 404, error(String.valueOf(exception.getMessage())));
        } catch (RuntimeException exception) {
            write(exchange, 500, error("内部错误: " + exception.getClass().getSimpleName()));
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/sessions")) {
            write(exchange, 200, sessionsNode());
            return;
        }
        if (path.equals("/api/compare")) {
            write(exchange, 200, compareNode(query(exchange.getRequestURI())));
            return;
        }
        if (path.startsWith(PREFIX)) {
            write(exchange, 200, runArtifact(path, query(exchange.getRequestURI())));
            return;
        }
        write(exchange, 404, error("未知端点: " + path));
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
