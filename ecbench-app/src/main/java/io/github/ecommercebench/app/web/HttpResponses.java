package io.github.ecommercebench.app.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;

/**
 * HTTP 响应的共享写出工具：JSON 之外的静态资源与文件下载都走这里。
 *
 * <p>从 {@link StaticAssets} 抽出，使 {@link ApiRouter} 的导出端点复用同一份写出逻辑，避免两处各写一遍
 * 而漂移。{@link #attachment} 的 {@code filename} 只接受调用方传入的 ASCII 常量、不接受任何用户输入，
 * 因此不存在 {@code Content-Disposition} 头注入面。
 */
final class HttpResponses {

    private HttpResponses() {}

    /** 写出定长响应体；调用方负责保证 {@code contentType} 带上 charset。 */
    static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 以附件形式写出 200 响应，触发浏览器下载。 */
    static void attachment(HttpExchange exchange, String contentType, String filename, byte[] body)
            throws IOException {
        exchange.getResponseHeaders()
                .set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        respond(exchange, 200, contentType, body);
    }
}
