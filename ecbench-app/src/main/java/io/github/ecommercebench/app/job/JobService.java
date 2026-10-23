package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.ConfigurationMerger;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.run.RunOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Web 提交运行的任务编排：内存注册表 + <b>单线程串行</b>执行器 + 状态机 + 尽力而为的取消。
 *
 * <p>串行的理由：一个 365 天 episode 是小时级任务且打满 LLM 配额；若允许多 job 并行，每个 job 内部 {@code
 * RunCoordinator} 又会开 {@code min(runs, 核数)} 个线程，线程数乘法级膨胀。job 内部仍可用 {@code runs} 并行。
 *
 * <p>进度从已落盘产物推导（balance CSV 行数、analysis.json 个数），完全不侵入 agent——agent 主循环没有任何进度回调接缝。
 *
 * <p>状态只存内存，服务重启即清空（历史产物仍在 {@code log/} 下，会话扫描照常可见）。
 *
 * <p>本类不依赖 {@code app.web}：队列已满抛 {@link JobQueueFullException}，取消结果以 {@link CancelOutcome} 返回，
 * HTTP 状态码由路由层映射。
 */
public final class JobService {

    /** 未结束任务（含正在跑的）总数上限。 */
    public static final int MAX_QUEUED = 8;

    private static final Pattern ANALYSIS = Pattern.compile("run_(\\d+)_analysis\\.json");

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ExecutorService executor;
    private final RunLauncher launcher;
    private final ModelKeyGuard keys;
    private final ConfigurationMerger merger = new ConfigurationMerger();
    private final RunOptions serverDefaults;
    private final Map<String, String> env;
    private final Path workingDir;
    private final ObjectMapper mapper;

    public JobService(
            RunLauncher launcher,
            ModelKeyGuard keys,
            RunOptions serverDefaults,
            Map<String, String> env,
            Path workingDir,
            ObjectMapper mapper) {
        this.launcher = launcher;
        this.keys = keys;
        this.serverDefaults = serverDefaults;
        this.env = Map.copyOf(env);
        this.workingDir = workingDir;
        this.mapper = mapper;
        this.executor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "ecbench-job");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /** 取消请求的结果；HTTP 状态码由路由层映射（NOT_FOUND → 404，ALREADY_FINISHED → 409）。 */
    public enum CancelOutcome {
        /** 已置为 CANCELLED 并中断了执行线程。 */
        CANCELLED,
        /** 没有这个任务 id。 */
        NOT_FOUND,
        /** 任务已是终态，取消太晚。 */
        ALREADY_FINISHED
    }

    /**
     * 接受一次提交：校验参数与密钥后就入队，立即返回 QUEUED 任务。
     *
     * @throws IllegalArgumentException 参数非法或密钥缺失
     * @throws JobQueueFullException 未结束任务数已达 {@link #MAX_QUEUED}
     */
    public Job submit(JsonNode body) {
        JobRequest request = JobRequest.parse(body, serverDefaults);
        keys.requireReady(request.model());
        synchronized (jobs) {
            long pending = jobs.values().stream().filter(job -> !job.state().terminal()).count();
            if (pending >= MAX_QUEUED) {
                throw new JobQueueFullException("任务队列已满（上限 " + MAX_QUEUED + " 个未结束任务）");
            }
            Job job = new Job(UUID.randomUUID().toString(), request);
            jobs.put(job.id(), job);
            job.attach(executor.submit(() -> execute(job)));
            return job;
        }
    }

    /** 按 id 查任务；不存在时返回空 Optional，由调用方决定状态码。 */
    public Optional<Job> find(String id) {
        synchronized (jobs) {
            return Optional.ofNullable(jobs.get(id));
        }
    }

    /** 按提交顺序返回全部任务的快照。 */
    public List<Job> list() {
        synchronized (jobs) {
            return new ArrayList<>(jobs.values());
        }
    }

    /**
     * 尽力而为地取消：先落 CANCELLED 终态，再中断执行线程。
     *
     * <p>中断会级联到 {@code RunCoordinator} 阻塞的 {@code invokeAll}，进而 {@code cancel(true)} 各 run 线程；但
     * agent 主循环不检查中断标志，若中断落在纯计算段要等下一个阻塞点才生效。已产出的部分产物留在磁盘。
     */
    public CancelOutcome cancel(String id) {
        Optional<Job> found = find(id);
        if (found.isEmpty()) {
            return CancelOutcome.NOT_FOUND;
        }
        Job job = found.get();
        synchronized (job) {
            if (job.state().terminal()) {
                return CancelOutcome.ALREADY_FINISHED;
            }
            job.markCancelled();
            Future<?> future = job.future();
            if (future != null) {
                future.cancel(true);
            }
            return CancelOutcome.CANCELLED;
        }
    }

    /** 关停执行器：中断在跑的任务并有限等待，不承诺优雅收尾。 */
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 等待全部任务到达终态；供测试与冒烟脚本使用。 */
    public void awaitQuiescence(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            boolean busy;
            synchronized (jobs) {
                busy = jobs.values().stream().anyMatch(job -> !job.state().terminal());
            }
            if (!busy) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("任务未在 " + timeout + " " + unit + " 内结束");
    }

    /** 会话根目录：serverDefaults.logDir() 为空时回退 workingDir/log，与 RunComponentFactory 同口径。 */
    public Path logDir() {
        String override = serverDefaults.logDir();
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return workingDir.toAbsolutePath().normalize().resolve("log");
    }

    /** 全部任务的 JobView 数组，按提交顺序。 */
    public ArrayNode jobsNode() {
        ArrayNode array = mapper.createArrayNode();
        for (Job job : list()) {
            array.add(jobNode(job));
        }
        return array;
    }

    /** 单个任务的 JobView。 */
    public ObjectNode jobNode(Job job) {
        JobRequest request = job.request();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", job.id());
        node.put("state", job.state().name());
        node.put("model", request.model());
        node.put("submittedAt", String.valueOf(job.submittedAt()));
        putInstant(node, "startedAt", job.startedAt());
        putInstant(node, "finishedAt", job.finishedAt());
        putText(node, "sessionName", job.sessionName());

        ObjectNode echoed = node.putObject("request");
        echoed.put("maxDays", request.maxDays());
        echoed.put("maxTurns", request.maxTurns());
        echoed.put("runs", request.runs());
        if (request.seed() == null) {
            echoed.putNull("seed");
        } else {
            echoed.put("seed", request.seed());
        }

        node.set("progress", progressNode(job));
        ArrayNode outcomes = outcomesNode(job);
        if (outcomes == null) {
            node.putNull("outcomes");
        } else {
            node.set("outcomes", outcomes);
        }
        putText(node, "error", job.error());
        return node;
    }

    /** 在 job 线程上执行一次运行；任何 Throwable 都收敛为终态，绝不让异常逃逸到执行器。 */
    private void execute(Job job) {
        if (!job.markRunning()) {
            return;
        }
        try {
            BenchmarkOptions options = merger.merge(job.request(), env, workingDir);
            List<RunOutcome> outcomes =
                    launcher.launch(options, dir -> job.setSessionName(dir.getFileName().toString()));
            job.complete(outcomes);
        } catch (Throwable failure) {
            if (job.state() == JobState.CANCELLED || Thread.currentThread().isInterrupted()) {
                job.markCancelled();
            } else {
                job.fail(failure);
            }
        }
    }

    /** 进度从已落盘产物推导；会话目录或文件缺失时全 0，绝不抛异常。 */
    private ObjectNode progressNode(Job job) {
        ObjectNode node = mapper.createObjectNode();
        int runsTotal = job.request().runs();
        int days = 0;
        int runsDone = 0;
        String sessionName = job.sessionName();
        if (sessionName != null) {
            Path session = logDir().resolve(sessionName);
            days = maxBalanceRows(session, runsTotal);
            runsDone = countAnalysisFiles(session);
        }
        node.put("days", days);
        node.put("maxDays", job.request().maxDays());
        node.put("runsTotal", runsTotal);
        node.put("runsDone", runsDone);
        return node;
    }

    /** 各 run 的 balance.csv 数据行数（去表头）取最大值；文件缺失或不可读按 0 计。 */
    private static int maxBalanceRows(Path session, int runsTotal) {
        int max = 0;
        for (int run = 0; run < runsTotal; run++) {
            Path csv = session.resolve("balance").resolve("run_" + run + "_daily_balance.csv");
            max = Math.max(max, countDataRows(csv));
        }
        return max;
    }

    /** 数据行数 = 非空行数 - 1（表头），下限 0。写一半的文件（无尾换行）也算最后一行。 */
    private static int countDataRows(Path csv) {
        if (!Files.isRegularFile(csv)) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            int nonBlank = 0;
            for (String line : lines) {
                if (!line.isBlank()) {
                    nonBlank++;
                }
            }
            return Math.max(0, nonBlank - 1);
        } catch (IOException | RuntimeException exception) {
            return 0;
        }
    }

    /** metrics/ 下 run_<i>_analysis.json 的个数，正则与 SessionScanner 同口径。 */
    private static int countAnalysisFiles(Path session) {
        Path metrics = session.resolve("metrics");
        if (!Files.isDirectory(metrics)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(metrics)) {
            return (int) files.map(file -> file.getFileName().toString())
                    .map(ANALYSIS::matcher)
                    .filter(Matcher::matches)
                    .count();
        } catch (IOException | RuntimeException exception) {
            return 0;
        }
    }

    /** 终态且有结果时输出各 run 的 index / canonicalReason / finalDay，否则返回 null 由调用方写 JSON null。 */
    private ArrayNode outcomesNode(Job job) {
        List<RunOutcome> outcomes = job.outcomes();
        if (outcomes == null) {
            return null;
        }
        ArrayNode array = mapper.createArrayNode();
        for (RunOutcome outcome : outcomes) {
            ObjectNode node = array.addObject();
            node.put("index", outcome.index());
            if (outcome.succeeded() && outcome.result() != null) {
                node.put("canonicalReason", outcome.result().canonicalReason());
                node.put("finalDay", outcome.result().finalDay());
                node.put("succeeded", true);
            } else {
                node.putNull("canonicalReason");
                node.putNull("finalDay");
                node.put("succeeded", false);
            }
        }
        return array;
    }

    /** 可空文本字段显式写 JSON null（而非省略），维持前端形状稳定。 */
    private static void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, String.valueOf(value));
        }
    }
}
