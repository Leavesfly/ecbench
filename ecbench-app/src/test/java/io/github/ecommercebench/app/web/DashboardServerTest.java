package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.cli.CliRunOptions;
import io.github.ecommercebench.app.job.JobService;
import io.github.ecommercebench.app.job.ModelKeyGuard;
import io.github.ecommercebench.app.run.RunOutcome;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 以随机端口拉起 dashboard，端到端验证静态页、各 JSON 接口、写侧端点与越界/缺失/跨域的错误映射。
 *
 * <p>models_config.json 放在<b>独立</b>的 {@code @TempDir} 里：{@code SessionScanner} 会把 logDir 下每个非 {@code .}
 * 前缀的子目录都当成会话，若把配置写进 log 目录，会话列表会莫名多出一项。
 */
class DashboardServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG_JSON =
            "{\"npc_tools\":{\"provider\":\"openai\",\"model\":\"gpt-4o-mini\"},"
                    + "\"models\":{\"gpt\":{\"provider\":\"openai\",\"model_name\":\"gpt-x\"}}}";

    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir Path log;
    @TempDir Path config;

    private DashboardServer server;
    private JobService jobService;
    private ModelCatalogService models;

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

        Files.createDirectories(config);
        Files.writeString(config.resolve("models_config.json"), CONFIG_JSON);
        models = new ModelCatalogService(newKeyGuard(Map.of("OPENAI_API_KEY", "sk-test")), MAPPER);
        jobService = newJobService((options, onSession) -> List.of(new RunOutcome(0, null, null)));
        server = new DashboardServer(log, "127.0.0.1", 0, Map.of(), models, jobService);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private ModelKeyGuard newKeyGuard(Map<String, String> env) {
        return new ModelKeyGuard(config.resolve("models_config.json"), env, MAPPER);
    }

    /**
     * 工作目录必须指向放着最小 models_config.json 的 {@code config}，不能用 {@code Path.of(".")}：
     * 后者会让 {@code ConfigurationMerger} 向上探测到仓库真实的 models_config.json，里面没有 {@code gpt}
     * 这个键，job 会在 merge 阶段就 FAILED，所有需要任务真跑起来的断言全塌。
     */
    private JobService newJobService(io.github.ecommercebench.app.job.RunLauncher launcher) {
        return new JobService(
                launcher,
                newKeyGuard(Map.of("OPENAI_API_KEY", "sk-test")),
                new CliRunOptions(),
                Map.of(),
                config,
                MAPPER);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String origin) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if ("DELETE".equals(method)) {
            builder.DELETE();
        } else if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /* -------------------------------------------------------------- 导出端点 */

    /**
     * 把 run_0 的余额 CSV 重写为三行数据，使 XChart 有足够点可绘。
     *
     * <p>只在本用例内改写，<b>不动 {@code @BeforeEach} 共享 fixture</b>：共享 fixture 只有一行数据，
     * 而其它用例（如任务进度推导）对行数有断言，改它会造成无关回归。
     */
    private void widenBalanceCsv() throws Exception {
        Files.writeString(
                log.resolve("2026_qwen3.7-plus/balance/run_0_daily_balance.csv"),
                "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,"
                        + "storage_charged\n"
                        + "2026-01-01,100000.00,0.00,100000.00,0,0,0.00\n"
                        + "2026-01-02,99000.00,0.00,99000.00,1,0,0.00\n"
                        + "2026-01-03,98000.00,0.00,98000.00,1,0,0.00\n");
    }

    /** 以字节读响应体，用于校验 PNG 魔数。 */
    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** PNG 魔数 89 50 4E 47；断言它即断言拿到的是服务端真实渲染的图而非空壳。 */
    private static void assertPngMagic(byte[] body) {
        assertThat(body.length).isGreaterThan(0);
        assertThat(body[0] & 0xFF).isEqualTo(0x89);
        assertThat(body[1] & 0xFF).isEqualTo(0x50);
        assertThat(body[2] & 0xFF).isEqualTo(0x4E);
        assertThat(body[3] & 0xFF).isEqualTo(0x47);
    }

    @Test
    void exportsBalancePngWithAttachmentHeader() throws Exception {
        widenBalanceCsv();

        HttpResponse<byte[]> response =
                getBytes("/api/export/balance.png?sessions=2026_qwen3.7-plus");

        assertThat(response.statusCode()).isEqualTo(200);
        // firstValue 返回 Optional<String>，而 AssertJ 的 OptionalAssert.contains 断言的是「恰好持有该值」
        // 而非子串包含，故先 orElse("") 取出字符串再断言。
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("image/png");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment")
                .contains("balance.png");
        assertPngMagic(response.body());
    }

    @Test
    void exportsRadarPng() throws Exception {
        HttpResponse<byte[]> response =
                getBytes("/api/export/radar.png?sessions=2026_qwen3.7-plus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("radar.png");
        assertPngMagic(response.body());
    }

    @Test
    void exportsRankingCsvAndJsonAndMatrixCsv() throws Exception {
        HttpResponse<String> csv = get("/api/export/ranking.csv?sessions=2026_qwen3.7-plus");
        HttpResponse<String> json = get("/api/export/ranking.json?sessions=2026_qwen3.7-plus");
        HttpResponse<String> matrix = get("/api/export/matrix.csv?sessions=2026_qwen3.7-plus");

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).contains("text/csv");
        assertThat(csv.body()).startsWith("rank,model,primary_raw");
        assertThat(json.statusCode()).isEqualTo(200);
        assertThat(json.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(json.body()).contains("\"models\"");
        assertThat(matrix.statusCode()).isEqualTo(200);
        assertThat(matrix.body()).startsWith("session,metric,mean,std,n");
    }

    @Test
    void exportDefaultsToDiscoveredSessionsWhenUnspecified() throws Exception {
        widenBalanceCsv();

        assertThat(get("/api/export/matrix.csv").statusCode()).isEqualTo(200);
        assertThat(getBytes("/api/export/balance.png").statusCode()).isEqualTo(200);
    }

    @Test
    void exportErrorMappingMatchesReadOnlyEndpoints() throws Exception {
        widenBalanceCsv();

        assertThat(get("/api/export/matrix.csv?sessions=bad%20name").statusCode()).isEqualTo(400);
        assertThat(get("/api/export/matrix.csv?sessions=2026_absent").statusCode()).isEqualTo(404);
        assertThat(get("/api/export/nope.csv").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/export/matrix.csv").statusCode()).isEqualTo(405);
    }

    @Test
    void balancePngWithoutAnyCsvIsNotFound() throws Exception {
        Files.delete(log.resolve("2026_qwen3.7-plus/balance/run_0_daily_balance.csv"));

        HttpResponse<String> response =
                get("/api/export/balance.png?sessions=2026_qwen3.7-plus");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("没有可绘制的余额 CSV");
    }

    /* ------------------------------------------------------------ 既有只读契约 */

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
    void chatboxWithoutTrajectoryIsNotFound() throws Exception {
        assertThat(get("/api/sessions/2026_qwen3.7-plus/chatbox?run=0").statusCode()).isEqualTo(404);
    }

    @Test
    void unknownButWellFormedSessionIsNotFound() throws Exception {
        assertThat(get("/api/sessions/2026_absent/analysis?run=0").statusCode()).isEqualTo(404);
    }

    @Test
    void malformedSessionNameIsBadRequest() throws Exception {
        assertThat(get("/api/sessions/bad%20name/analysis?run=0").statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsTraversalAndUnknownEndpoint() throws Exception {
        assertThat(get("/api/sessions/..%2F..%2Fetc/analysis?run=0").statusCode()).isIn(400, 404);
        assertThat(get("/api/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsNonGetRequest() throws Exception {
        assertThat(send("POST", "/api/sessions").statusCode()).isEqualTo(405);
    }

    /* ------------------------------------------------------------ 方法分发 */

    @Test
    void deleteOnReadOnlyEndpointIsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = send("DELETE", "/api/compare");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).contains("error");
    }

    @Test
    void putOnSessionArtifactIsMethodNotAllowed() throws Exception {
        assertThat(send("PUT", "/api/sessions/2026_qwen3.7-plus/analysis").statusCode()).isEqualTo(405);
    }

    @Test
    void postToUnknownEndpointIsNotFoundNotMethodNotAllowed() throws Exception {
        assertThat(send("POST", "/api/nope").statusCode()).isEqualTo(404);
    }

    /* ------------------------------------------------------------ /api/models */

    @Test
    void modelsEndpointReportsKeyReadiness() throws Exception {
        HttpResponse<String> response = get("/api/models");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode node = MAPPER.readTree(response.body());
        assertThat(node.get("models")).hasSize(1);
        assertThat(node.get("models").get(0).get("key").asText()).isEqualTo("gpt");
        assertThat(node.get("models").get(0).get("keyReady").asBoolean()).isTrue();
        assertThat(node.get("npc").get("modelName").asText()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void modelsEndpointFlagsMissingKey() throws Exception {
        ModelCatalogService noKeys = new ModelCatalogService(newKeyGuard(Map.of()), MAPPER);

        JsonNode node = noKeys.node();

        assertThat(node.get("models").get(0).get("keyReady").asBoolean()).isFalse();
        assertThat(node.get("models").get(0).get("missingEnv").asText()).isEqualTo("OPENAI_API_KEY");
        assertThat(node.get("npc").get("missingEnv").asText()).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void modelsEndpointRejectsPost() throws Exception {
        assertThat(send("POST", "/api/models").statusCode()).isEqualTo(405);
    }

    /* ------------------------------------------------------------ POST /api/jobs */

    @Test
    void submitsAJobAndReturnsAccepted() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "{\"model\":\"gpt\",\"maxDays\":2}", null);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(MAPPER.readTree(response.body()).get("id").asText()).isNotEmpty();
        assertThat(response.body()).contains("\"state\"");
    }

    @Test
    void rejectsMalformedJsonWithoutEchoingTheBody() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "not-json", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("not-json");
    }

    @Test
    void rejectsEmptyBody() throws Exception {
        assertThat(post("/api/jobs", "", null).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsMissingModelWithBadRequest() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "{\"maxDays\":2}", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("model");
    }

    @Test
    void rejectsUnknownModelKeyWithBadRequest() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "{\"model\":\"nope\"}", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("nope");
    }

    @Test
    void rejectsNonPositiveNumberWithBadRequest() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "{\"model\":\"gpt\",\"maxDays\":0}", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("maxDays");
    }

    @Test
    void rejectsForeignOriginWithForbidden() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "{\"model\":\"gpt\"}", "http://evil.example");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void allowsSameOriginOnLoopbackAndLocalhost() throws Exception {
        int port = server.port();

        assertThat(post("/api/jobs", "{\"model\":\"gpt\"}", "http://127.0.0.1:" + port).statusCode())
                .isEqualTo(202);
        assertThat(post("/api/jobs", "{\"model\":\"gpt\"}", "http://localhost:" + port).statusCode())
                .isEqualTo(202);
    }

    @Test
    void rejectsOversizedBodyWithPayloadTooLarge() throws Exception {
        String huge = "{\"model\":\"gpt\",\"pad\":\"" + "x".repeat(70_000) + "\"}";

        assertThat(post("/api/jobs", huge, null).statusCode()).isEqualTo(413);
    }

    @Test
    void rejectsQueueBeyondLimitWithConflict() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobService blocking =
                newJobService(
                        (options, onSession) -> {
                            try {
                                release.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        });
        try (DashboardServer target = new DashboardServer(log, "127.0.0.1", 0, Map.of(), models, blocking)) {
            target.start();
            for (int i = 0; i < JobService.MAX_QUEUED; i++) {
                assertThat(postTo(target, "{\"model\":\"gpt\"}").statusCode()).isEqualTo(202);
            }

            assertThat(postTo(target, "{\"model\":\"gpt\"}").statusCode()).isEqualTo(409);
            release.countDown();
        }
    }

    private HttpResponse<String> postTo(DashboardServer target, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + "/api/jobs"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /* ------------------------------------------------------------ GET / DELETE /api/jobs */

    @Test
    void listsSubmittedJobs() throws Exception {
        post("/api/jobs", "{\"model\":\"gpt\"}", null);
        jobService.awaitQuiescence(10, TimeUnit.SECONDS);

        HttpResponse<String> response = get("/api/jobs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("jobs")).hasSize(1);
    }

    @Test
    void jobViewCarriesProgressRequestAndOutcomes() throws Exception {
        String id = MAPPER.readTree(post("/api/jobs", "{\"model\":\"gpt\",\"maxDays\":9}", null).body())
                .get("id")
                .asText();
        jobService.awaitQuiescence(10, TimeUnit.SECONDS);

        JsonNode view = MAPPER.readTree(send("GET", "/api/jobs/" + id).body());

        assertThat(view.get("id").asText()).isEqualTo(id);
        assertThat(view.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(view.get("model").asText()).isEqualTo("gpt");
        assertThat(view.get("request").get("maxDays").asInt()).isEqualTo(9);
        assertThat(view.get("progress").get("maxDays").asInt()).isEqualTo(9);
        assertThat(view.get("progress").get("days").asInt()).isZero();
        assertThat(view.get("sessionName").isNull()).isTrue();
        assertThat(view.get("outcomes")).hasSize(1);
        assertThat(view.get("error").isNull()).isTrue();
    }

    @Test
    void unknownJobIdIsNotFound() throws Exception {
        assertThat(send("GET", "/api/jobs/absent").statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/jobs/absent").statusCode()).isEqualTo(404);
    }

    @Test
    void deleteFinishedJobIsConflict() throws Exception {
        String id = MAPPER.readTree(post("/api/jobs", "{\"model\":\"gpt\"}", null).body())
                .get("id")
                .asText();
        jobService.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(send("DELETE", "/api/jobs/" + id).statusCode()).isEqualTo(409);
    }

    @Test
    void deleteRunningJobCancelsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobService blocking =
                newJobService(
                        (options, onSession) -> {
                            started.countDown();
                            try {
                                release.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        });
        try (DashboardServer target = new DashboardServer(log, "127.0.0.1", 0, Map.of(), models, blocking)) {
            target.start();
            String id = MAPPER.readTree(postTo(target, "{\"model\":\"gpt\"}").body()).get("id").asText();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + "/api/jobs/" + id))
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"state\":\"CANCELLED\"");
            release.countDown();
        }
    }

    @Test
    void malformedJobIdIsBadRequest() throws Exception {
        assertThat(send("GET", "/api/jobs/a/b").statusCode()).isEqualTo(400);
    }

    @Test
    void postToSingleJobEndpointIsMethodNotAllowed() throws Exception {
        assertThat(send("POST", "/api/jobs/any").statusCode()).isEqualTo(405);
    }

    @Test
    void emptyJobListIsReturnedBeforeAnySubmission() throws Exception {
        JsonNode node = MAPPER.readTree(get("/api/jobs").body());

        assertThat(node.get("jobs")).isEmpty();
    }
}
