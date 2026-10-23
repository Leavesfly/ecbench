package io.github.ecommercebench.app.job;

import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;

/**
 * 单个 Web 提交任务的可变状态载体。
 *
 * <p>状态迁移全部在 {@code synchronized} 方法内完成，且<b>只允许从非终态迁出</b>，因此「取消」与「正常完成」同时到达时
 * 以先落地的终态为准，不会出现 CANCELLED 被 SUCCEEDED 覆盖（或反之）。
 *
 * <p>非状态字段用 {@code volatile}：HTTP 工作线程读、job 执行线程写，需要跨线程可见性。
 */
public final class Job {

    private final String id;
    private final JobRequest request;
    private final Instant submittedAt = Instant.now();

    private JobState state = JobState.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String sessionName;
    private volatile String error;
    private volatile List<RunOutcome> outcomes;
    private volatile Future<?> future;
    private volatile Path tempJobFile;

    public Job(String id, JobRequest request) {
        this.id = id;
        this.request = request;
    }

    public String id() {
        return id;
    }

    public JobRequest request() {
        return request;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String sessionName() {
        return sessionName;
    }

    public String error() {
        return error;
    }

    public List<RunOutcome> outcomes() {
        return outcomes;
    }

    public Future<?> future() {
        return future;
    }

    /** 可空的临时 job 文件路径（服务端为上传内容生成），仅用于终态后清理。 */
    public Path tempJobFile() {
        return tempJobFile;
    }

    /** 登记服务端为上传内容生成的临时 job 文件；未上传时不调用，保持 null。 */
    public void setTempJobFile(Path path) {
        this.tempJobFile = path;
    }

    public synchronized JobState state() {
        return state;
    }

    /** 登记执行句柄，供取消时中断。 */
    public void attach(Future<?> handle) {
        this.future = handle;
    }

    /** 会话目录建好后回填目录名，供进度推导与前端跳转。 */
    public void setSessionName(String name) {
        this.sessionName = name;
    }

    /** QUEUED → RUNNING；若已被取消则返回 false，调用方应立即返回不执行。 */
    public synchronized boolean markRunning() {
        if (state.terminal()) {
            return false;
        }
        state = JobState.RUNNING;
        startedAt = Instant.now();
        return true;
    }

    /** 取消：仅在非终态生效。 */
    public synchronized void markCancelled() {
        if (state.terminal()) {
            return;
        }
        state = JobState.CANCELLED;
        finishedAt = Instant.now();
    }

    /** 正常收尾：仅在仍处于 RUNNING 时生效（已被取消则保持 CANCELLED）。 */
    public synchronized void complete(List<RunOutcome> results) {
        if (state != JobState.RUNNING) {
            return;
        }
        this.outcomes = results == null ? List.of() : List.copyOf(results);
        state = JobState.SUCCEEDED;
        finishedAt = Instant.now();
    }

    /** 失败收尾：仅在非终态生效；错误文本只留异常简单类名与消息首行，不含堆栈。 */
    public synchronized void fail(Throwable failure) {
        if (state.terminal()) {
            return;
        }
        this.error = describe(failure);
        state = JobState.FAILED;
        finishedAt = Instant.now();
    }

    /** 取异常简单类名 + 消息首行，避免把堆栈（及其中可能出现的密钥）回传前端。 */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String firstLine = message == null ? "" : message.split("\n", 2)[0].trim();
        String name = failure.getClass().getSimpleName();
        return firstLine.isEmpty() ? name : name + ": " + firstLine;
    }
}
