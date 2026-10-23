package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.cli.CliRunOptions;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 任务编排契约：串行、状态机、取消、队列上限。全部用假 launcher，不碰 LLM 与仿真引擎。
 *
 * <p>密钥校验用真实 {@link ModelKeyGuard}（指向工作目录里那份最小 models_config.json）+ 一份假 env，
 * 因此「缺密钥则拒绝提交」这条路径也被真测到。{@code ConfigurationMerger} 同样会在 job 线程上解析
 * models_config.json，故两个协作方共用同一份最小配置。
 */
class JobServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG_JSON =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {"gpt": {"provider": "openai", "model_name": "gpt-x"}}
                    }
                    """;

    @TempDir Path workingDir;

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

    /** 真实 guard：指向工作目录的最小注册表，密钥用假值补齐，于是 gpt 就绪而未知键仍被拒。 */
    private ModelKeyGuard readyGuard() {
        return new ModelKeyGuard(
                workingDir.resolve("models_config.json"), Map.of("OPENAI_API_KEY", "sk-test"), MAPPER);
    }

    private JobService serviceWith(RunLauncher launcher) {
        service =
                new JobService(
                        launcher, readyGuard(), new CliRunOptions(), Map.of(), workingDir, MAPPER);
        return service;
    }

    private static ObjectNode body(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    @Test
    void submittedJobRunsAndSucceeds() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            launches.incrementAndGet();
                            return List.of(new RunOutcome(0, null, null));
                        });

        Job job = svc.submit(body("{\"model\":\"gpt\",\"maxDays\":3}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(launches.get()).isEqualTo(1);
        assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.outcomes()).hasSize(1);
        assertThat(job.startedAt()).isNotNull();
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    void launcherFailureMarksJobFailed() throws Exception {
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            throw new IllegalStateException("kaboom");
                        });

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.error()).isEqualTo("IllegalStateException: kaboom");
    }

    @Test
    void mergedOptionsReachTheLauncher() throws Exception {
        AtomicInteger maxDays = new AtomicInteger();
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            maxDays.set(options.runConfig().maxDays());
                            return List.of();
                        });

        svc.submit(body("{\"model\":\"gpt\",\"maxDays\":11}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(maxDays.get()).isEqualTo(11);
    }

    @Test
    void jobsRunStrictlySerially() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                            firstStarted.countDown();
                            try {
                                releaseFirst.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            concurrent.decrementAndGet();
                            return List.of();
                        });

        Job first = svc.submit(body("{\"model\":\"gpt\"}"));
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        Job second = svc.submit(body("{\"model\":\"gpt\"}"));

        assertThat(second.state()).isEqualTo(JobState.QUEUED);
        releaseFirst.countDown();
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(peak.get()).isEqualTo(1);
        assertThat(first.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(second.state()).isEqualTo(JobState.SUCCEEDED);
    }

    @Test
    void cancelInterruptsRunningJob() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            started.countDown();
                            try {
                                Thread.sleep(60_000);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("被中断");
                            }
                            return List.of();
                        });

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(svc.cancel(job.id())).isEqualTo(JobService.CancelOutcome.CANCELLED);
        svc.awaitQuiescence(10, TimeUnit.SECONDS);
        assertThat(job.state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void cancelQueuedJobPreventsItFromRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            launches.incrementAndGet();
                            started.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        });

        svc.submit(body("{\"model\":\"gpt\"}"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        Job queued = svc.submit(body("{\"model\":\"gpt\"}"));

        assertThat(svc.cancel(queued.id())).isEqualTo(JobService.CancelOutcome.CANCELLED);
        release.countDown();
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(launches.get()).isEqualTo(1);
        assertThat(queued.state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void cancelFinishedJobReportsAlreadyFinished() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());
        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(svc.cancel(job.id())).isEqualTo(JobService.CancelOutcome.ALREADY_FINISHED);
        assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
    }

    @Test
    void unknownJobIdIsReportedNotNullThrown() {
        JobService svc = serviceWith((options, onSession) -> List.of());

        assertThat(svc.find("nope")).isEmpty();
        assertThat(svc.cancel("nope")).isEqualTo(JobService.CancelOutcome.NOT_FOUND);
    }

    @Test
    void queueBeyondLimitIsRejected() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            started.countDown();
                            try {
                                release.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        });

        svc.submit(body("{\"model\":\"gpt\"}"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 1; i < JobService.MAX_QUEUED; i++) {
            svc.submit(body("{\"model\":\"gpt\"}"));
        }

        assertThatThrownBy(() -> svc.submit(body("{\"model\":\"gpt\"}")))
                .isInstanceOf(JobQueueFullException.class);
        release.countDown();
    }

    @Test
    void listingIsInSubmissionOrder() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            try {
                                release.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        });

        Job a = svc.submit(body("{\"model\":\"gpt\"}"));
        Job b = svc.submit(body("{\"model\":\"gpt\"}"));
        release.countDown();
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(svc.list()).containsExactly(a, b);
        assertThat(svc.jobsNode()).hasSize(2);
    }

    @Test
    void sessionCallbackReachesTheJob() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            onSession.accept(workingDir.resolve("20261014_100001_gpt"));
                            done.countDown();
                            return List.of();
                        });

        Job job = svc.submit(body("{\"model\":\"gpt\"}"));
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(job.sessionName()).isEqualTo("20261014_100001_gpt");
    }

    @Test
    void submitPropagatesRequestValidationErrors() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());

        assertThatThrownBy(() -> svc.submit(body("{\"maxDays\":3}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThat(svc.list()).isEmpty();
    }

    @Test
    void submitRejectsUnknownModelKeyBeforeQueueing() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            launches.incrementAndGet();
                            return List.of();
                        });

        assertThatThrownBy(() -> svc.submit(body("{\"model\":\"nope\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        assertThat(launches.get()).isZero();
        assertThat(svc.list()).isEmpty();
    }

    @Test
    void submitRejectsMissingNpcKeyBeforeQueueing() throws Exception {
        // guard 用一份没有 OPENAI_API_KEY 的 env：NPC 模型（openai）不就绪，即使主模型键存在也必须拒绝。
        ModelKeyGuard noKeys =
                new ModelKeyGuard(workingDir.resolve("models_config.json"), Map.of(), MAPPER);
        service =
                new JobService(
                        (options, onSession) -> List.of(), noKeys, new CliRunOptions(), Map.of(), workingDir, MAPPER);

        assertThatThrownBy(() -> service.submit(body("{\"model\":\"gpt\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_API_KEY");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void logDirFallsBackToWorkingDirLog() {
        JobService svc = serviceWith((options, onSession) -> List.of());

        assertThat(svc.logDir()).isEqualTo(workingDir.toAbsolutePath().normalize().resolve("log"));
    }
}
