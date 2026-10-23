package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 任务状态机：只允许从非终态迁出，取消与完成竞态时以先落地的终态为准。 */
class JobTest {

    private static JobRequest request() {
        return new JobRequest(
                "gpt", 16384, 4000, 365, 100000.0, 50.0, 128000, 1, null, null, null, null, null);
    }

    private static Job newJob() {
        return new Job("id-1", request());
    }

    @Test
    void startsQueuedWithSubmissionTimestamp() {
        Job job = newJob();

        assertThat(job.state()).isEqualTo(JobState.QUEUED);
        assertThat(job.submittedAt()).isNotNull();
        assertThat(job.startedAt()).isNull();
        assertThat(job.sessionName()).isNull();
        assertThat(JobState.QUEUED.terminal()).isFalse();
    }

    @Test
    void runningThenSucceededRecordsOutcomesAndFinishTime() {
        Job job = newJob();

        assertThat(job.markRunning()).isTrue();
        job.complete(List.of(new RunOutcome(0, null, null)));

        assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(JobState.SUCCEEDED.terminal()).isTrue();
        assertThat(job.startedAt()).isNotNull();
        assertThat(job.finishedAt()).isNotNull();
        assertThat(job.outcomes()).hasSize(1);
    }

    @Test
    void cancelAfterCompletionIsRefused() {
        Job job = newJob();
        job.markRunning();
        job.complete(List.of());

        job.markCancelled();

        assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
    }

    @Test
    void completionAfterCancelIsRefused() {
        Job job = newJob();
        job.markRunning();
        job.markCancelled();

        job.complete(List.of());

        assertThat(job.state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void failureAfterCancelIsRefused() {
        Job job = newJob();
        job.markRunning();
        job.markCancelled();

        job.fail(new IllegalStateException("late"));

        assertThat(job.state()).isEqualTo(JobState.CANCELLED);
        assertThat(job.error()).isNull();
    }

    @Test
    void markRunningOnCancelledJobReturnsFalse() {
        Job job = newJob();
        job.markCancelled();

        assertThat(job.markRunning()).isFalse();
        assertThat(job.state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void failureRecordsSimpleClassNameAndFirstLineOnly() {
        Job job = newJob();
        job.markRunning();

        job.fail(new IllegalStateException("boom\nat some.Stack(Frame.java:1)"));

        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.error()).isEqualTo("IllegalStateException: boom");
        assertThat(job.error()).doesNotContain("Stack");
    }

    @Test
    void failureWithoutMessageFallsBackToClassName() {
        Job job = newJob();
        job.markRunning();

        job.fail(new IllegalStateException());

        assertThat(job.error()).isEqualTo("IllegalStateException");
    }

    @Test
    void sessionNameIsRecordedForProgressLookup() {
        Job job = newJob();

        job.setSessionName("20261014_100001_gpt");

        assertThat(job.sessionName()).isEqualTo("20261014_100001_gpt");
    }

    @Test
    void nullOutcomesBecomeAnEmptyImmutableList() {
        Job job = newJob();
        job.markRunning();

        job.complete(null);

        assertThat(job.outcomes()).isEmpty();
        assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
    }

    /** 临时 job 文件字段未上传时必须为 null，否则清理逻辑会误删不相干的路径。 */
    @Test
    void tempJobFileIsNullByDefaultAndRoundTrips() {
        Job job = newJob();

        assertThat(job.tempJobFile()).isNull();

        job.setTempJobFile(Path.of("/tmp/x.jsonl"));

        assertThat(job.tempJobFile()).isEqualTo(Path.of("/tmp/x.jsonl"));

        job.setTempJobFile(null);

        assertThat(job.tempJobFile()).isNull();
    }
}
