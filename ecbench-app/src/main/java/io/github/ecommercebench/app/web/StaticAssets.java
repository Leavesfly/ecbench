package io.github.ecommercebench.app.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 从 classpath 的 {@code web/} 目录提供 dashboard 静态资源。
 *
 * <p>仅映射三个已知路径（入口页、脚本、样式），其余一律 404，因此不存在目录穿越面；读取与写出均为只读操作。
 */
public final class StaticAssets implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String resource = resolve(exchange.getRequestURI().getPath());
        if (resource == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", bytes("not found"));
            return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                respond(exchange, 404, "text/plain; charset=utf-8", bytes("missing asset"));
                return;
            }
            respond(exchange, 200, contentType(resource), in.readAllBytes());
        }
    }

    private static String resolve(String path) {
        return switch (path) {
            case "/", "/index.html" -> "web/index.html";
            case "/app.js" -> "web/app.js";
            case "/app.css" -> "web/app.css";
            default -> null;
        };
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
