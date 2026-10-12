package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 以随机端口拉起 dashboard，端到端验证静态页、各 JSON 接口与越界/缺失的错误映射。 */
class DashboardServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private DashboardServer server;

    @TempDir Path log;

    @BeforeEach
    void startFixtureServer() throws Exception {
        Path session = log.resolve("2026_qwen3.7-plus");
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(
                session.resolve("metrics/run_0_analysis.json"),
                "{\"profitability\":{\"final_balance\":95000.0,\"initial_balance\":100000.0,"
                        + "\"peak_total_assets\":100000.0,\"peak_drawdown\":0.0,"
                        + "\"stores_opened\":1,\"store_reopens\":0},"
                        + "\"operational_efficiency\":{\"total_tool_calls\":8}}");
        Files.createDirectories(session.resolve("balance"));
        Files.writeString(
                session.resolve("balance/run_0_daily_balance.csv"),
                "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,storage_charged\n"
                        + "2026-01-01,100000.00,0.00,100000.00,0,0,0.00\n");
        server = new DashboardServer(log, "127.0.0.1", 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesIndexPage() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("E-CommerceBench");
    }

    @Test
    void listsDiscoveredSessions() throws Exception {
        HttpResponse<String> response = get("/api/sessions");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("2026_qwen3.7-plus").contains("\"runs\":1");
    }

    @Test
    void compareReturnsRadarAndRanking() throws Exception {
        HttpResponse<String> response = get("/api/compare?sessions=2026_qwen3.7-plus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"radar\"").contains("\"ranking\"");
    }

    @Test
    void compareDefaultsToAllSessionsWhenUnspecified() throws Exception {
        HttpResponse<String> response = get("/api/compare");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("2026_qwen3.7-plus");
    }

    @Test
    void analysisPassthroughAndMissingRun() throws Exception {
        assertThat(get("/api/sessions/2026_qwen3.7-plus/analysis?run=0").body()).contains("final_balance");
        assertThat(get("/api/sessions/2026_qwen3.7-plus/analysis?run=99").statusCode()).isEqualTo(404);
    }

    @Test
    void balanceReturnsSeriesWithAuthoritativeColumn() throws Exception {
        HttpResponse<String> response = get("/api/sessions/2026_qwen3.7-plus/balance?run=0");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("total_balance").contains("2026-01-01");
    }

    @Test
    void chatboxReturnsEmptySuppliersWhenNoTrajectory() throws Exception {
        assertThat(get("/api/sessions/2026_qwen3.7-plus/chatbox?run=0").statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsTraversalAndUnknownEndpoint() throws Exception {
        assertThat(get("/api/sessions/..%2F..%2Fetc/analysis?run=0").statusCode()).isIn(400, 404);
        assertThat(get("/api/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsNonGetRequest() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/sessions"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }
}
