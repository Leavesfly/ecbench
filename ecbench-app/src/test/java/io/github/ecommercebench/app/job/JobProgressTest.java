package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.cli.CliRunOptions;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 进度从已落盘产物推导：balance.csv 行数（去表头）取各 run 最大值，analysis.json 个数即完成 run 数。 */
class JobProgressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG_JSON =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {"gpt": {"provider": "openai", "model_name": "gpt-x"}}
                    }
                    """;

    @TempDir Path workingDir;
    @TempDir Path logDir;

    private JobService service;

    @BeforeEach
    void writeMinimalModelRegistry() throws Exception {
        Files.writeString(workingDir.resolve("models_config.json"), CONFIG_JSON);
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.shutdown();
        }
    }

    /** logDir 指向 @TempDir 的服务端参数；ConfigurationMerger 与进度推导共用它。 */
    private RunOptions server() {
        CliRunOptions options = new CliRunOptions();
        new picocli.CommandLine(options).parseArgs("--log-dir", logDir.toString());
        return options;
    }

    private JobService serviceWith(RunLauncher launcher) {
        ModelKeyGuard guard =
                new ModelKeyGuard(
                        workingDir.resolve("models_config.json"), Map.of("OPENAI_API_KEY", "sk-test"), MAPPER);
        service = new JobService(launcher, guard, server(), Map.of(), workingDir, MAPPER);
        return service;
    }

    private static ObjectNode body(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    private static void writeBalance(Path session, int run, int dataRows) throws Exception {
        Path file = session.resolve("balance").resolve("run_" + run + "_daily_balance.csv");
        Files.createDirectories(file.getParent());
        StringBuilder csv = new StringBuilder("date,total_balance\n");
        for (int i = 0; i < dataRows; i++) {
            csv.append("2026-01-").append(String.format("%02d", i + 1)).append(",100000.00\n");
        }
        Files.writeString(file, csv.toString());
    }

    private static void writeAnalysis(Path session, int run) throws Exception {
        Path file = session.resolve("metrics").resolve("run_" + run + "_analysis.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
    }

    @Test
    void daysComeFromBalanceCsvRowCount() throws Exception {
        Path session = logDir.resolve("20261014_100001_gpt");
        writeBalance(session, 0, 7);
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\",\"maxDays\":30}"));
        job.setSessionName(session.getFileName().toString());

        JsonNode progress = svc.jobNode(job).get("progress");
        assertThat(progress.get("days").asInt()).isEqualTo(7);
        assertThat(progress.get("maxDays").asInt()).isEqualTo(30);
        assertThat(progress.get("runsTotal").asInt()).isEqualTo(1);
        assertThat(progress.get("runsDone").asInt()).isZero();
    }

    @Test
    void daysTakeTheMaxAcrossRuns() throws Exception {
        Path session = logDir.resolve("20261014_100002_gpt");
        writeBalance(session, 0, 3);
        writeBalance(session, 1, 9);
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\",\"runs\":2}"));
        job.setSessionName(session.getFileName().toString());

        assertThat(svc.jobNode(job).get("progress").get("days").asInt()).isEqualTo(9);
    }

    @Test
    void runsDoneCountsAnalysisFiles() throws Exception {
        Path session = logDir.resolve("20261014_100003_gpt");
        writeAnalysis(session, 0);
        writeAnalysis(session, 1);
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\",\"runs\":3}"));
        job.setSessionName(session.getFileName().toString());

        JsonNode progress = svc.jobNode(job).get("progress");
        assertThat(progress.get("runsDone").asInt()).isEqualTo(2);
        assertThat(progress.get("runsTotal").asInt()).isEqualTo(3);
    }

    @Test
    void unrelatedFilesInMetricsAreNotCounted() throws Exception {
        Path session = logDir.resolve("20261014_100007_gpt");
        writeAnalysis(session, 0);
        Files.writeString(session.resolve("metrics/run_0_negotiation_metrics.json"), "{}");
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        job.setSessionName(session.getFileName().toString());

        assertThat(svc.jobNode(job).get("progress").get("runsDone").asInt()).isEqualTo(1);
    }

    @Test
    void queuedJobWithoutSessionDegradesToZeros() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job queued = svc.submit(body("{\"model\":\"gpt\",\"runs\":2,\"maxDays\":30}"));

        JsonNode progress = svc.jobNode(queued).get("progress");
        assertThat(queued.sessionName()).isNull();
        assertThat(progress.get("days").asInt()).isZero();
        assertThat(progress.get("runsDone").asInt()).isZero();
        assertThat(progress.get("runsTotal").asInt()).isEqualTo(2);
        assertThat(progress.get("maxDays").asInt()).isEqualTo(30);
    }

    @Test
    void sessionDirectoryThatNeverAppearedDegradesToZeros() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        job.setSessionName("20261014_999999_absent");

        assertThat(svc.jobNode(job).get("progress").get("days").asInt()).isZero();
    }

    @Test
    void partialCsvWithoutTrailingNewlineIsStillCounted() throws Exception {
        Path session = logDir.resolve("20261014_100004_gpt");
        Path file = session.resolve("balance/run_0_daily_balance.csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "date,total_balance\n2026-01-01,1.00\n2026-01-02,2.00");
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        job.setSessionName(session.getFileName().toString());

        assertThat(svc.jobNode(job).get("progress").get("days").asInt()).isEqualTo(2);
    }

    @Test
    void headerOnlyCsvYieldsZeroDays() throws Exception {
        Path session = logDir.resolve("20261014_100005_gpt");
        writeBalance(session, 0, 0);
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        job.setSessionName(session.getFileName().toString());

        assertThat(svc.jobNode(job).get("progress").get("days").asInt()).isZero();
    }

    @Test
    void jobViewCarriesTheDocumentedShape() throws Exception {
        JobService svc =
                serviceWith((options, onSession) -> List.of(new RunOutcome(0, null, null)));

        Job job = svc.submit(body("{\"model\":\"gpt\",\"maxDays\":5,\"seed\":7}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        JsonNode view = svc.jobNode(job);
        assertThat(view.get("id").asText()).isEqualTo(job.id());
        assertThat(view.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(view.get("model").asText()).isEqualTo("gpt");
        assertThat(view.get("submittedAt").asText()).isNotEmpty();
        assertThat(view.get("startedAt").isNull()).isFalse();
        assertThat(view.get("finishedAt").isNull()).isFalse();
        assertThat(view.get("sessionName").isNull()).isTrue();
        assertThat(view.get("request").get("maxDays").asInt()).isEqualTo(5);
        assertThat(view.get("request").get("seed").asLong()).isEqualTo(7L);
        assertThat(view.get("outcomes")).hasSize(1);
        assertThat(view.get("outcomes").get(0).get("index").asInt()).isZero();
        assertThat(view.get("outcomes").get(0).get("succeeded").asBoolean()).isFalse();
        assertThat(view.get("error").isNull()).isTrue();
    }

    @Test
    void absentSeedIsWrittenAsJsonNull() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));

        assertThat(svc.jobNode(job).get("request").get("seed").isNull()).isTrue();
    }

    @Test
    void outcomesAreNullUntilTheJobSucceeds() throws Exception {
        CountingLauncher launcher = new CountingLauncher();
        JobService svc = serviceWith(launcher);

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(svc.jobNode(job).get("outcomes")).hasSize(1);
        assertThat(launcher.calls).isEqualTo(1);
    }

    @Test
    void jobsNodeListsEveryJobInSubmissionOrder() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());

        Job a = svc.submit(body("{\"model\":\"gpt\"}"));
        Job b = svc.submit(body("{\"model\":\"gpt\"}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(svc.jobsNode()).hasSize(2);
        assertThat(svc.jobsNode().get(0).get("id").asText()).isEqualTo(a.id());
        assertThat(svc.jobsNode().get(1).get("id").asText()).isEqualTo(b.id());
    }

    /** 记录调用次数的假 launcher。 */
    private static final class CountingLauncher implements RunLauncher {
        private int calls;

        @Override
        public List<RunOutcome> launch(
                io.github.ecommercebench.app.config.BenchmarkOptions options,
                java.util.function.Consumer<Path> onSessionCreated) {
            calls++;
            return List.of(new RunOutcome(0, null, null));
        }
    }
}
