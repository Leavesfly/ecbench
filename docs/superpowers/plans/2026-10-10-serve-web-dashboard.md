# `serve` Web Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增只读子命令 `ecbench serve`，用 JDK 内置 HttpServer 提供本地 Web dashboard，对 `log/` 已有产物做多 run / 多会话对比。

**Architecture:** `serve` 子命令装配一个 `com.sun.net.httpserver` 服务（默认 `127.0.0.1:8080`），后端 4 个 service 复用 `ecbench-evaluation` 既有能力把产物转成 JSON；前端为 `classpath:/web/` 下的静态单页（Chart.js CDN）。仅显式运行 `serve` 才绑定端口，`run`/`evaluate` 行为与 `WebApplicationType.NONE` 启动零改动。

**Tech Stack:** Java 17、Picocli 4.7.7、Jackson databind（随 evaluation 传递依赖）、Apache commons-csv（随 evaluation）、JUnit 5 + AssertJ（`spring-boot-starter-test`）、`java.net.http.HttpClient`（测试）、Chart.js（前端 CDN）。

**Spec:** `docs/superpowers/specs/2026-10-10-serve-web-dashboard-design.md`

---

## Global Constraints

- JDK 17；**不新增重量级依赖**：Web 仅用 JDK `com.sun.net.httpserver`，禁止引入 `spring-boot-starter-web`/Tomcat/MVC。
- Checkstyle：行宽 ≤ 120、**禁止 star import**、单方法 ≤ 150 行。
- Spotless（google-java-format）：每次改动源码后、跑测试前先 `mvn -q spotless:apply`。
- 新增生产类补**中文 Javadoc**，风格对齐现有 `ecbench-app` 类（见 `EvaluationCommand`）。
- 只读：所有接口仅 GET；`{name}` 校验 `^[A-Za-z0-9._-]+$` 且不含 `..`，解析后目录必须仍在 `--log-dir` 规范化根之下。
- 端口默认 `8080`（`0` 为随机端口，用于测试），host 默认 `127.0.0.1`。
- 退出码：`serve` 正常停止返回 `0`；`--log-dir` 不存在等配置错误返回 `2`。
- Git 安全：提交**只 add 本特性涉及的文件**，禁止 `git add .`；不得把进入会话前既有的无关暂存改动带入提交。

**包与目录约定：** 生产代码位于 `io.github.ecommercebench.app.web`（`ServeCommand` 位于 `io.github.ecommercebench.app.cli` 以便 `BenchmarkCommand` 引用）。静态资源位于 `ecbench-app/src/main/resources/web/`。测试位于 `ecbench-app/src/test/java/io/github/ecommercebench/app/web/`。

**共享 record（Task 1 定义，跨任务复用）：**
```java
// io.github.ecommercebench.app.web.SessionInfo
record SessionInfo(String name, String modelKey, int runCount, boolean hasBalance) {}
```

**自定义异常（Task 2 定义）：**
```java
// io.github.ecommercebench.app.web.ArtifactNotFoundException
class ArtifactNotFoundException extends RuntimeException { ArtifactNotFoundException(String msg){super(msg);} }
```

---

## Task 1: SessionScanner（会话发现与名称安全校验）

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/SessionInfo.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/SessionScanner.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/SessionScannerTest.java`

**Interfaces:**
- Consumes: 无（纯目录扫描 + 正则）。
- Produces:
  - `SessionInfo(String name, String modelKey, int runCount, boolean hasBalance)`
  - `SessionScanner(Path logDir)`
  - `List<SessionInfo> scan()`（按 name 升序）
  - `Path sessionDir(String name)`（非法名抛 `IllegalArgumentException`；合法返回 `logDir/name`）
  - `static boolean isValidName(String name)`

- [ ] **Step 1: 写失败测试**

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionScannerTest {

    private void writeAnalysis(Path sessionDir, int run) throws Exception {
        Path metrics = sessionDir.resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(
                metrics.resolve("run_" + run + "_analysis.json"),
                "{\"reward\":{\"final_score\":1.0}}");
    }

    @Test
    void scansSessionsWithRunCountsAndModelKey(@TempDir Path log) throws Exception {
        Path s1 = log.resolve("20261010_173016_qwen3.7-plus");
        Path s2 = log.resolve("20261010_180000_gpt-5.6-sol");
        writeAnalysis(s1, 0);
        writeAnalysis(s2, 0);
        writeAnalysis(s2, 1);
        Files.createDirectories(s2.resolve("balance"));

        List<SessionInfo> infos = new SessionScanner(log).scan();

        assertThat(infos).extracting(SessionInfo::name)
                .containsExactly("20261010_173016_qwen3.7-plus", "20261010_180000_gpt-5.6-sol");
        assertThat(infos.get(0).modelKey()).isEqualTo("qwen3.7-plus");
        assertThat(infos.get(0).runCount()).isEqualTo(1);
        assertThat(infos.get(0).hasBalance()).isFalse();
        assertThat(infos.get(1).runCount()).isEqualTo(2);
        assertThat(infos.get(1).hasBalance()).isTrue();
    }

    @Test
    void rejectsPathTraversal(@TempDir Path log) {
        SessionScanner scanner = new SessionScanner(log);
        assertThatThrownBy(() -> scanner.sessionDir("../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scanner.sessionDir("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(scanner.isValidName("20261010_qwen3.7-plus")).isTrue();
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -q -pl ecbench-app -am -Dtest=SessionScannerTest test`
Expected: 编译失败（`SessionScanner`/`SessionInfo` 未定义）。

- [ ] **Step 3: 实现 SessionInfo 与 SessionScanner**

```java
package io.github.ecommercebench.app.web;

/** dashboard 发现的一个会话（一个时间戳_模型键目录）及其 run 概览。 */
public record SessionInfo(String name, String modelKey, int runCount, boolean hasBalance) {}
```

```java
package io.github.ecommercebench.app.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描 {@code --log-dir} 下的会话目录，并安全地把会话名解析为目录路径。
 *
 * <p>会话 = logDir 的直接子目录；run 数取 {@code metrics/run_<i>_analysis.json} 的个数；{@code modelKey} 取目录名最后一个下划线
 * 之后的段（无下划线则回退整个名字）。名称校验拒绝路径穿越，解析结果必须仍在 logDir 之下。
 */
public final class SessionScanner {

    private static final Pattern ANALYSIS = Pattern.compile("run_(\\d+)_analysis\\.json");

    private final Path logDir;

    public SessionScanner(Path logDir) {
        this.logDir = logDir.toAbsolutePath().normalize();
    }

    /** 按名称升序返回所有会话概览；logDir 不存在时返回空列表。 */
    public List<SessionInfo> scan() {
        if (!Files.isDirectory(logDir)) {
            return List.of();
        }
        List<SessionInfo> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(logDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(this::toInfo)
                    .sorted(Comparator.comparing(SessionInfo::name))
                    .forEach(result::add);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法列出日志目录: " + logDir, exception);
        }
        return result;
    }

    /** 把会话名解析为目录；非法名或不落在 logDir 内的越界名抛 {@link IllegalArgumentException}。 */
    public Path sessionDir(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("非法会话名: " + name);
        }
        Path resolved = logDir.resolve(name).normalize();
        if (!resolved.startsWith(logDir) || !Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("会话不存在或越界: " + name);
        }
        return resolved;
    }

    /** 名称仅允许字母数字与点/下划线/连字符，且不含 {@code ..}。 */
    public static boolean isValidName(String name) {
        return name != null
                && !name.contains("..")
                && Pattern.matches("[A-Za-z0-9._-]+", name);
    }

    private SessionInfo toInfo(Path dir) {
        String name = dir.getFileName().toString();
        List<Integer> runs = runIndexes(dir);
        boolean hasBalance = Files.isDirectory(dir.resolve("balance")) && !runs.isEmpty();
        return new SessionInfo(name, modelKey(name), runs.size(), hasBalance);
    }

    private List<Integer> runIndexes(Path dir) {
        Path metrics = dir.resolve("metrics");
        List<Integer> indexes = new ArrayList<>();
        if (!Files.isDirectory(metrics)) {
            return indexes;
        }
        try (Stream<Path> files = Files.list(metrics)) {
            files.map(p -> p.getFileName().toString())
                    .map(ANALYSIS::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .forEach(m -> indexes.add(Integer.parseInt(m.group(1))));
        } catch (IOException exception) {
            throw new UncheckedIOException("无法列出 metrics: " + metrics, exception);
        }
        java.util.Collections.sort(indexes);
        return indexes;
    }

    private static String modelKey(String name) {
        int idx = name.lastIndexOf('_');
        return idx >= 0 && idx < name.length() - 1 ? name.substring(idx + 1) : name;
    }
}
```

注：`runIndexes` 目前从 `metrics/` 目录取 run 号；Task 2 的产物读取改用 `RunLayoutResolver` 兼容 legacy 平铺布局，`scan` 的 run 计数以 `metrics/` 为准即可（演示布局为 organized）。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -q -pl ecbench-app -am -Dtest=SessionScannerTest test`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
mvn -q spotless:apply
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/SessionInfo.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/SessionScanner.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/SessionScannerTest.java
git commit -m "feat(app): add SessionScanner for serve dashboard"
```

---

## Task 2: RunArtifactService（单 run 下钻产物读取）

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ArtifactNotFoundException.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/RunArtifactService.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/RunArtifactServiceTest.java`

**Interfaces:**
- Consumes: `RunLayoutResolver`、`AnalysisJsonReader`、`BalanceCsvReader`、`ChatboxExtractor`（均在 `io.github.ecommercebench.evaluation[.io]`）。
- Produces:
  - `ArtifactNotFoundException(String)`
  - `RunArtifactService(ObjectMapper mapper)`
  - `JsonNode analysis(Path sessionDir, int run)`
  - `JsonNode balance(Path sessionDir, int run)` → `{"name","run","series":[{"date","total_balance","bank_balance","platform_wallet","open_stores","warehouse_items"}]}`
  - `JsonNode chatbox(Path sessionDir, int run)` → `{"name","run","suppliers":[{"supplier","messages":[{"role","content"}]}]}`

- [ ] **Step 1: 写失败测试**

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunArtifactServiceTest {

    private final RunArtifactService service = new RunArtifactService(new ObjectMapper());

    @Test
    void readsAnalysisAsPassthrough(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(
                session.resolve("metrics/run_0_analysis.json"),
                "{\"reward\":{\"termination_reason\":\"env_terminated\"}}");
        JsonNode node = service.analysis(session, 0);
        assertThat(node.path("reward").path("termination_reason").asText())
                .isEqualTo("env_terminated");
    }

    @Test
    void readsBalanceSeries(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("balance"));
        Files.writeString(
                session.resolve("balance/run_0_daily_balance.csv"),
                "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,storage_charged\n"
                        + "2026-01-01,100000.00,0.00,100000.00,0,0,0.00\n"
                        + "2026-01-02,95386.90,0.00,95386.90,3,0,0.00\n");
        JsonNode node = service.balance(session, 0);
        assertThat(node.path("series")).hasSize(2);
        assertThat(node.path("series").get(1).path("total_balance").asDouble()).isEqualTo(95386.90);
    }

    @Test
    void missingArtifactThrowsNotFound(@TempDir Path session) {
        assertThatThrownBy(() -> service.analysis(session, 0))
                .isInstanceOf(ArtifactNotFoundException.class);
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -q -pl ecbench-app -am -Dtest=RunArtifactServiceTest test`
Expected: 编译失败（类未定义）。

- [ ] **Step 3: 实现异常与服务**

```java
package io.github.ecommercebench.app.web;

/** 请求的 run 产物文件不存在时抛出，映射为 HTTP 404。 */
public class ArtifactNotFoundException extends RuntimeException {
    public ArtifactNotFoundException(String message) {
        super(message);
    }
}
```

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.ChatboxExtractor;
import io.github.ecommercebench.evaluation.io.AnalysisJsonReader;
import io.github.ecommercebench.evaluation.io.BalanceCsvReader;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.model.BalancePoint;
import io.github.ecommercebench.evaluation.model.ChatboxConversation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读取单个 run 的下钻产物（analysis 原样透传 / balance 序列 / chatbox 分组），复用 evaluation 模块的读取器。
 *
 * <p>路径经 {@link RunLayoutResolver} 解析以兼容 organized 与 legacy 布局；产物文件缺失抛
 * {@link ArtifactNotFoundException}。
 */
public final class RunArtifactService {

    private final RunLayoutResolver resolver = new RunLayoutResolver();
    private final AnalysisJsonReader analysisReader = new AnalysisJsonReader();
    private final BalanceCsvReader balanceReader = new BalanceCsvReader();
    private final ChatboxExtractor chatboxExtractor = new ChatboxExtractor();
    private final ObjectMapper mapper;

    public RunArtifactService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 原样返回该 run 的 analysis.json 树。 */
    public JsonNode analysis(Path sessionDir, int run) {
        Path file = resolver.resolve(sessionDir).analysisJson(run);
        require(file);
        return analysisReader.read(file);
    }

    /** 把余额 CSV 转成 {name,run,series:[...]}，total_balance 为权威列。 */
    public JsonNode balance(Path sessionDir, int run) {
        Path file = resolver.resolve(sessionDir).balanceCsv(run);
        require(file);
        List<BalancePoint> points = balanceReader.read(file);
        ObjectNode root = mapper.createObjectNode();
        root.put("name", sessionDir.getFileName().toString());
        root.put("run", run);
        ArrayNode series = root.putArray("series");
        for (BalancePoint p : points) {
            ObjectNode row = series.addObject();
            row.put("date", p.date());
            row.put("total_balance", p.totalBalance());
            row.put("bank_balance", p.bankBalance());
            row.put("platform_wallet", p.platformWallet());
            row.put("open_stores", p.openStores());
            row.put("warehouse_items", p.warehouseItems());
        }
        return root;
    }

    /** 按供应商分组返回 chatbox 对话，解析每行 role/content。 */
    public JsonNode chatbox(Path sessionDir, int run) {
        Path file = resolver.resolve(sessionDir).messagesJsonl(run);
        require(file);
        Map<String, ChatboxConversation> conversations = chatboxExtractor.extract(file);
        ObjectNode root = mapper.createObjectNode();
        root.put("name", sessionDir.getFileName().toString());
        root.put("run", run);
        ArrayNode suppliers = root.putArray("suppliers");
        for (ChatboxConversation conversation : conversations.values()) {
            ObjectNode node = suppliers.addObject();
            node.put("supplier", conversation.supplier());
            ArrayNode messages = node.putArray("messages");
            for (String raw : conversation.rawLines()) {
                JsonNode parsed = parseOrNull(raw);
                if (parsed == null) {
                    continue;
                }
                ObjectNode message = messages.addObject();
                message.put("role", parsed.path("role").asText(""));
                message.put("content", parsed.path("content").asText(""));
            }
        }
        return root;
    }

    private static void require(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ArtifactNotFoundException("产物不存在: " + file.getFileName());
        }
    }

    private JsonNode parseOrNull(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (java.io.IOException exception) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `mvn -q -pl ecbench-app -am -Dtest=RunArtifactServiceTest test`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 提交**

```bash
mvn -q spotless:apply
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ArtifactNotFoundException.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/RunArtifactService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/RunArtifactServiceTest.java
git commit -m "feat(app): add RunArtifactService for serve drilldown"
```

---

## Task 3: CompareService（对比 / 雷达 / 排名 JSON）

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/CompareService.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/CompareServiceTest.java`

**Interfaces:**
- Consumes: `RunComparator.compare(List<Path>)`、`CrossModelProfiler.profile(ComparisonReport)`、`RankingTable.rank(NormalizedProfile)`、`ProfileAxis.values()`、`NormalizedProfile`/`SessionComparison`/`MetricStat`。
- Produces:
  - `CompareService(ObjectMapper mapper)`
  - `JsonNode compare(List<Path> sessionDirs)` →
    `{"sessions":[{"name","runCount","metrics":[{"label","mean","std","n","fallback"}]}], "radar":{"axes":[str],"series":[{"name","values":[num|null]}],"median":[num|null]}, "ranking":[{"rank","name","score":num|null}]}`
    `sessionDirs` 为空时返回 `{"sessions":[],"radar":{"axes":[],"series":[],"median":[]},"ranking":[]}`。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareServiceTest {

    private final CompareService service = new CompareService(new ObjectMapper());

    private void session(Path dir, double finalBalance) throws Exception {
        Files.createDirectories(dir.resolve("metrics"));
        Files.writeString(
                dir.resolve("metrics/run_0_analysis.json"),
                "{\"profitability\":{\"final_balance\":" + finalBalance
                        + ",\"initial_balance\":100000.0,\"peak_total_assets\":100000.0,"
                        + "\"peak_drawdown\":0.0,\"stores_opened\":1,\"store_reopens\":0},"
                        + "\"operational_efficiency\":{\"total_tool_calls\":10}}");
    }

    @Test
    void buildsSessionsRadarAndRanking(@TempDir Path log) throws Exception {
        Path a = log.resolve("2026_a-model");
        Path b = log.resolve("2026_b-model");
        session(a, 90000);
        session(b, 120000);

        JsonNode node = service.compare(List.of(a, b));

        assertThat(node.path("sessions")).hasSize(2);
        assertThat(node.path("radar").path("axes")).hasSize(7);
        assertThat(node.path("radar").path("series")).hasSize(2);
        assertThat(node.path("radar").path("series").get(0).path("values")).hasSize(7);
        // ranking 按 final_balance 降序：b(120000) 第一
        assertThat(node.path("ranking").get(0).path("name").asText()).isEqualTo("2026_b-model");
        assertThat(node.path("ranking").get(0).path("rank").asInt()).isEqualTo(1);
    }

    @Test
    void emptyInputYieldsEmptyShape() {
        JsonNode node = service.compare(List.of());
        assertThat(node.path("sessions")).isEmpty();
        assertThat(node.path("radar").path("series")).isEmpty();
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -q -pl ecbench-app -am -Dtest=CompareServiceTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 CompareService**

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.CrossModelProfiler;
import io.github.ecommercebench.evaluation.ProfileAxis;
import io.github.ecommercebench.evaluation.RankingTable;
import io.github.ecommercebench.evaluation.RankingTable.RankedModel;
import io.github.ecommercebench.evaluation.RunComparator;
import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;
import io.github.ecommercebench.evaluation.model.NormalizedProfile.ModelProfile;

import java.nio.file.Path;
import java.util.List;

/**
 * 复用 evaluation 的比较/画像/排名能力，产出 dashboard 所需的会话指标、七轴雷达与排名 JSON。
 *
 * <p>雷达轴顺序与 {@link ProfileAxis#values()} 一致；NaN 归一化值在 JSON 中写为 {@code null}（对齐 {@link RankingTable} 既有约定）。
 */
public final class CompareService {

    private final ObjectMapper mapper;

    public CompareService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 对给定会话目录聚合指标、归一化雷达并排名。 */
    public JsonNode compare(List<Path> sessionDirs) {
        ComparisonReport report = new RunComparator().compare(sessionDirs);
        NormalizedProfile profile = new CrossModelProfiler().profile(report);
        ObjectNode root = mapper.createObjectNode();
        root.set("sessions", sessionsNode(report));
        root.set("radar", radarNode(profile));
        root.set("ranking", rankingNode(new RankingTable().rank(profile)));
        return root;
    }

    private ArrayNode sessionsNode(ComparisonReport report) {
        ArrayNode sessions = mapper.createArrayNode();
        for (SessionComparison session : report.sessions()) {
            ObjectNode node = sessions.addObject();
            node.put("name", session.name());
            node.put("runCount", session.runCount());
            ArrayNode metrics = node.putArray("metrics");
            for (MetricStat stat : session.metrics()) {
                ObjectNode metric = metrics.addObject();
                metric.put("label", stat.label());
                putNumber(metric, "mean", stat.mean());
                putNumber(metric, "std", stat.std());
                metric.put("n", stat.n());
                metric.put("fallback", stat.fallback());
            }
        }
        return sessions;
    }

    private ObjectNode radarNode(NormalizedProfile profile) {
        ObjectNode radar = mapper.createObjectNode();
        ArrayNode axes = radar.putArray("axes");
        for (ProfileAxis axis : ProfileAxis.values()) {
            axes.add(axis.label());
        }
        ArrayNode series = radar.putArray("series");
        for (ModelProfile model : profile.models()) {
            ObjectNode node = series.addObject();
            node.put("name", model.name());
            ArrayNode values = node.putArray("values");
            for (Double value : model.normalizedValues()) {
                addNumber(values, value);
            }
        }
        ArrayNode median = radar.putArray("median");
        for (Double value : profile.medianNormalized()) {
            addNumber(median, value);
        }
        return radar;
    }

    private ArrayNode rankingNode(List<RankedModel> ranked) {
        ArrayNode array = mapper.createArrayNode();
        for (RankedModel model : ranked) {
            ObjectNode node = array.addObject();
            node.put("rank", model.rank());
            node.put("name", model.name());
            putNumber(node, "score", nanToNull(model.primaryRaw()));
        }
        return array;
    }

    private static Double nanToNull(double value) {
        return Double.isNaN(value) ? null : value;
    }

    private static void putNumber(ObjectNode node, String field, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void addNumber(ArrayNode node, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.addNull();
        } else {
            node.add(value);
        }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `mvn -q -pl ecbench-app -am -Dtest=CompareServiceTest test`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
mvn -q spotless:apply
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/CompareService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/CompareServiceTest.java
git commit -m "feat(app): add CompareService reusing evaluation profile/ranking"
```

---

## Task 4: StaticAssets（classpath 静态单页）

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/StaticAssets.java`
- Create: `ecbench-app/src/main/resources/web/index.html`
- Create: `ecbench-app/src/main/resources/web/app.css`
- Create: `ecbench-app/src/main/resources/web/app.js`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/StaticAssetsTest.java`

**Interfaces:**
- Consumes: JDK `com.sun.net.httpserver.HttpExchange`、classpath 资源 `web/*`。
- Produces: `StaticAssets implements HttpHandler`；`GET /`→`index.html`（`text/html`）、`/app.js`→`application/javascript`、`/app.css`→`text/css`、其余→404。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class StaticAssetsTest {

    @Test
    void bundlesDashboardAssetsOnClasspath() {
        for (String path : List.of("web/index.html", "web/app.css", "web/app.js")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                assertThat(in).as("classpath 资源缺失: " + path).isNotNull();
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
```
（在测试文件顶部 `import java.util.List;`）

- [ ] **Step 2: 运行验证失败**

Run: `mvn -q -pl ecbench-app -am -Dtest=StaticAssetsTest test`
Expected: 失败（`web/index.html` 等资源尚不存在）。

- [ ] **Step 3: 实现 StaticAssets 与最小前端资源**

```java
package io.github.ecommercebench.app.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 从 classpath {@code web/} 提供 dashboard 静态资源；未知路径 404。 */
public final class StaticAssets implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String resource = resolve(path);
        if (resource == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                respond(exchange, 404, "text/plain; charset=utf-8", "missing asset".getBytes(StandardCharsets.UTF_8));
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

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
```

`web/index.html`（最小骨架，引入 CDN Chart.js 与本地脚本；文案可为英文/中文）：

```html
<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>E-CommerceBench · Run 对比看板</title>
  <link rel="stylesheet" href="/app.css" />
  <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
</head>
<body>
  <header><h1>E-CommerceBench 多 Run 对比看板</h1><span id="status"></span></header>
  <section id="sessions"><h2>会话</h2><div id="session-list"></div>
    <button id="select-all">全选</button><button id="clear">清空</button></section>
  <section id="metrics"><h2>指标对比</h2><div id="metric-table"></div></section>
  <section id="profile"><h2>七轴雷达 + 排名</h2>
    <div class="row"><canvas id="radar"></canvas><div id="ranking"></div></div></section>
  <section id="balance"><h2>余额曲线叠加</h2><canvas id="balance-chart"></canvas></section>
  <section id="drilldown"><h2>单 Run 下钻</h2><div id="run-picker"></div>
    <pre id="analysis"></pre><div id="chatbox"></div></section>
  <script src="/app.js"></script>
</body>
</html>
```

`web/app.css`（最小可用样式）：

```css
body { font-family: system-ui, sans-serif; margin: 1.5rem; color: #222; }
h1 { font-size: 1.3rem; } section { margin-bottom: 2rem; }
.row { display: flex; gap: 1.5rem; align-items: flex-start; }
table { border-collapse: collapse; } th, td { border: 1px solid #ddd; padding: 4px 8px; font-size: .85rem; }
#status { color: #666; margin-left: .8rem; } canvas { max-width: 460px; max-height: 360px; }
pre { background:#f6f6f6; padding:.6rem; max-height:280px; overflow:auto; }
button { margin-left: .4rem; }
```

`web/app.js`（完整实现四视图 + 下钻；见 Step 3 代码）。核心逻辑：

```javascript
const api = (p) => fetch(p).then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); });
const state = { sessions: [], selected: new Set(), radarCharts: {}, };
async function boot() {
  state.sessions = await api('/api/sessions');
  renderSessions();
  document.getElementById('select-all').onclick = () => { state.sessions.forEach((s)=>state.selected.add(s.name)); renderSessions(); loadCompare(); };
  document.getElementById('clear').onclick = () => { state.selected.clear(); renderSessions(); loadCompare(); };
  if (state.sessions[0]) { state.selected.add(state.sessions[0].name); loadCompare(); }
}
function renderSessions() {
  const box = document.getElementById('session-list'); box.innerHTML = '';
  state.sessions.forEach((s) => {
    const label = document.createElement('label');
    const cb = document.createElement('input'); cb.type='checkbox'; cb.checked=state.selected.has(s.name);
    cb.onchange = () => { cb.checked ? state.selected.add(s.name) : state.selected.delete(s.name); loadCompare(); };
    label.append(cb, ` ${s.name} (runs=${s.runs})`); box.appendChild(label); box.append(' ');
  });
}
async function loadCompare() {
  const q = [...state.selected].map((n)=>'sessions='+encodeURIComponent(n)).join('&');
  try {
    const data = await api('/api/compare' + (q ? '?' + q : ''));
    document.getElementById('status').textContent = `已对比 ${data.sessions.length} 个会话`;
    renderMetricTable(data.sessions); renderRadar(data.radar); renderRanking(data.ranking); renderPicker(data.sessions.map((s)=>s.name));
  } catch (e) { document.getElementById('status').textContent = '加载失败: ' + e.message; }
}
function renderMetricTable(sessions) {
  const labels = sessions[0] ? sessions[0].metrics.map((m)=>m.label) : [];
  let html = '<table><tr><th>metric</th>' + sessions.map((s)=>`<th>${s.name}</th>`).join('') + '</tr>';
  labels.forEach((label, i) => {
    html += `<tr><td>${label}</td>` + sessions.map((s)=>`<td>${fmt(s.metrics[i])}</td>`).join('') + '</tr>';
  });
  document.getElementById('metric-table').innerHTML = html + '</table>';
}
const fmt = (m) => (m.mean==null ? (m.fallback ?? '-') : (m.std ? `${m.mean.toFixed(3)} ± ${m.std.toFixed(3)}` : m.mean.toFixed(3)));
function renderRadar(radar) {
  const ctx = document.getElementById('radar');
  if (state.radarCharts.radar) state.radarCharts.radar.destroy();
  const datasets = radar.series.map((s) => ({ label: s.name, data: s.values, fill: true }));
  datasets.push({ label: 'median', data: radar.median, borderDash: [4,4], fill: false });
  state.radarCharts.radar = new Chart(ctx, { type: 'radar', data: { labels: radar.axes, datasets } });
}
function renderRanking(ranking) {
  let html = '<table><tr><th>rank</th><th>name</th><th>score</th></tr>';
  ranking.forEach((r)=>{ html += `<tr><td>${r.rank}</td><td>${r.name}</td><td>${r.score==null?'n/a':r.score.toFixed(1)}</td></tr>`; });
  document.getElementById('ranking').innerHTML = html + '</table>';
}
function renderPicker(names) {
  const box = document.getElementById('run-picker'); box.innerHTML = '<span>下钻: </span>';
  names.forEach((n)=>{ const b=document.createElement('button'); b.textContent=n; b.onclick=()=>drill(n); box.appendChild(b); });
}
async function drill(name) {
  const a = await api(`/api/sessions/${encodeURIComponent(name)}/analysis?run=0`);
  document.getElementById('analysis').textContent = JSON.stringify(a, null, 2);
  const c = await api(`/api/sessions/${encodeURIComponent(name)}/chatbox?run=0`);
  document.getElementById('chatbox').innerHTML = (c.suppliers||[]).map((s)=>`<details><summary>${s.supplier}</summary>`
    + s.messages.map((m)=>`<div><b>${m.role}:</b> ${escapeHtml((m.content||'').slice(0,400))}</div>`).join('') + '</details>').join('');
  await overlayBalance(name);
}
async function overlayBalance(name) {
  const b = await api(`/api/sessions/${encodeURIComponent(name)}/balance?run=0`);
  const ctx = document.getElementById('balance-chart');
  if (state.radarCharts.balance) state.radarCharts.balance.destroy();
  state.radarCharts.balance = new Chart(ctx, { type:'line', data:{ labels: b.series.map((p)=>p.date),
    datasets:[{ label:name, data:b.series.map((p)=>p.total_balance) }] } });
}
const escapeHtml = (s) => s.replace(/[&<>]/g, (c)=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
boot();
```

- [ ] **Step 4: 运行验证通过**

Run: `mvn -q -pl ecbench-app -am -Dtest=StaticAssetsTest test`
Expected: PASS（3 个资源均在 classpath）。

- [ ] **Step 5: 提交**

```bash
mvn -q spotless:apply
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/StaticAssets.java \
        ecbench-app/src/main/resources/web/index.html \
        ecbench-app/src/main/resources/web/app.css \
        ecbench-app/src/main/resources/web/app.js \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/StaticAssetsTest.java
git commit -m "feat(app): add serve static dashboard assets and handler"
```

---

## Task 5: ApiRouter + DashboardServer + ServeCommand 装配（含集成测试）

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/BenchmarkCommand.java:27`（`subcommands` 增加 `ServeCommand.class`）
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java`

**Interfaces:**
- Consumes: `SessionScanner`、`CompareService`、`RunArtifactService`、`StaticAssets`、`ArtifactNotFoundException`（前四个任务产出）。
- Produces:
  - `ApiRouter(ObjectMapper mapper, SessionScanner scanner, CompareService compare, RunArtifactService artifacts)` implements `HttpHandler`
  - `DashboardServer(Path logDir, String host, int port)` implements `AutoCloseable`（`int port()` 实际端口；`close()` 停止）
  - `ServeCommand`（picocli `@Command(name="serve")`，`--log-dir`/`--port`/`--host`）

- [ ] **Step 1: 写失败集成测试**

```java
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

class DashboardServerTest {

    private DashboardServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir Path log;

    @BeforeEach
    void startFixture() throws Exception {
        Path s = log.resolve("2026_qwen3.7-plus");
        Files.createDirectories(s.resolve("metrics"));
        Files.writeString(
                s.resolve("metrics/run_0_analysis.json"),
                "{\"profitability\":{\"final_balance\":95000.0,\"initial_balance\":100000.0,"
                        + "\"peak_total_assets\":100000.0,\"peak_drawdown\":0.0,\"stores_opened\":1,\"store_reopens\":0},"
                        + "\"operational_efficiency\":{\"total_tool_calls\":8}}");
        Files.createDirectories(s.resolve("balance"));
        Files.writeString(
                s.resolve("balance/run_0_daily_balance.csv"),
                "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,storage_charged\n"
                        + "2026-01-01,100000.00,0.00,100000.00,0,0,0.00\n");
        server = new DashboardServer(log, "127.0.0.1", 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://127.0.0.1:" + server.port(); }

    @Test
    void servesIndex() throws Exception {
        assertThat(get("/").statusCode()).isEqualTo(200);
        assertThat(get("/").body()).contains("E-CommerceBench");
    }

    @Test
    void listsSessions() throws Exception {
        assertThat(get("/api/sessions").statusCode()).isEqualTo(200);
        assertThat(get("/api/sessions").body()).contains("2026_qwen3.7-plus");
    }

    @Test
    void compareReturnsRadarAndRanking() throws Exception {
        HttpResponse<String> res = get("/api/compare?sessions=2026_qwen3.7-plus");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"radar\"").contains("\"ranking\"");
    }

    @Test
    void analysisPassthroughAndMissingRun() throws Exception {
        assertThat(get("/api/sessions/2026_qwen3.7-plus/analysis?run=0").body()).contains("final_balance");
        assertThat(get("/api/sessions/2026_qwen3.7-plus/analysis?run=99").statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        assertThat(get("/api/sessions/..%2F..%2Fetc/analysis?run=0").statusCode()).isIn(400, 404);
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -q -pl ecbench-app -am -Dtest=DashboardServerTest test`
Expected: 编译失败（`DashboardServer`/`ApiRouter` 未定义）。

- [ ] **Step 3: 实现 ApiRouter、DashboardServer、ServeCommand 并注册子命令**

`ApiRouter.java`：

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 只读 JSON API 路由：解析 path/query，调用 service，映射异常到状态码。 */
public final class ApiRouter implements HttpHandler {

    private final ObjectMapper mapper;
    private final SessionScanner scanner;
    private final CompareService compare;
    private final RunArtifactService artifacts;

    public ApiRouter(ObjectMapper mapper, SessionScanner scanner, CompareService compare,
            RunArtifactService artifacts) {
        this.mapper = mapper;
        this.scanner = scanner;
        this.compare = compare;
        this.artifacts = artifacts;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, error(exception.getMessage()));
        } catch (ArtifactNotFoundException exception) {
            write(exchange, 404, error(exception.getMessage()));
        } catch (RuntimeException exception) {
            write(exchange, 500, error(String.valueOf(exception.getMessage())));
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, error("method not allowed"));
            return;
        }
        if (path.equals("/api/sessions")) {
            write(exchange, 200, sessionsNode());
            return;
        }
        if (path.equals("/api/compare")) {
            write(exchange, 200, compareNode(queryValues(exchange.getRequestURI())));
            return;
        }
        handleRunArtifact(exchange, path);
    }

    private void handleRunArtifact(HttpExchange exchange, String path) throws IOException {
        // /api/sessions/{name}/{kind}?run=i
        String[] parts = path.split("/");
        if (parts.length != 5 || !"api".equals(parts[1]) || !"sessions".equals(parts[2])) {
            write(exchange, 404, error("unknown endpoint"));
            return;
        }
        String name = parts[3];
        String kind = parts[4];
        int run = parseRun(queryValues(exchange.getRequestURI()).getOrDefault("run", List.of("0")).get(0));
        Path dir = scanner.sessionDir(name);
        JsonNode body = switch (kind) {
            case "analysis" -> artifacts.analysis(dir, run);
            case "balance" -> artifacts.balance(dir, run);
            case "chatbox" -> artifacts.chatbox(dir, run);
            default -> throw new IllegalArgumentException("未知产物类型: " + kind);
        };
        write(exchange, 200, body);
    }

    private JsonNode sessionsNode() {
        var array = mapper.createArrayNode();
        for (SessionInfo info : scanner.scan()) {
            var node = array.addObject();
            node.put("name", info.name());
            node.put("modelKey", info.modelKey());
            node.put("runs", info.runCount());
            node.put("hasBalance", info.hasBalance());
        }
        return array;
    }

    private JsonNode compareNode(java.util.Map<String, List<String>> query) {
        List<String> names = query.getOrDefault("sessions", List.of());
        List<Path> dirs = new ArrayList<>();
        for (String name : names) {
            dirs.add(scanner.sessionDir(name));
        }
        if (dirs.isEmpty()) {
            scanner.scan().stream().limit(12).forEach(info -> dirs.add(scanner.sessionDir(info.name())));
        }
        return compare.compare(dirs);
    }

    private static int parseRun(String value) {
        if (value == null || !value.matches("\\d+")) {
            throw new IllegalArgumentException("非法 run: " + value);
        }
        return Integer.parseInt(value);
    }

    private static java.util.Map<String, List<String>> queryValues(URI uri) {
        java.util.Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return map;
    }

    private JsonNode error(String message) {
        var node = mapper.createObjectNode();
        node.put("error", message);
        return node;
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
```

`DashboardServer.java`：

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/** 基于 JDK HttpServer 的 dashboard 服务生命周期封装（默认只绑本地回环）。 */
public final class DashboardServer implements AutoCloseable {

    private final HttpServer httpServer;

    public DashboardServer(Path logDir, String host, int port) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        SessionScanner scanner = new SessionScanner(logDir);
        ApiRouter api = new ApiRouter(mapper, scanner, new CompareService(mapper), new RunArtifactService(mapper));
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.httpServer.createContext("/api", api);
        this.httpServer.createContext("/", new StaticAssets());
        this.httpServer.setExecutor(Executors.newFixedThreadPool(4));
    }

    /** 启动服务。 */
    public void start() {
        httpServer.start();
    }

    /** 实际绑定端口（port=0 时由系统分配）。 */
    public int port() {
        return httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }
}
```

`ServeCommand.java`：

```java
package io.github.ecommercebench.app.cli;

import io.github.ecommercebench.app.web.DashboardServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * 子命令 {@code serve}：启动只读 Web dashboard，浏览 {@code log-dir} 下的 run 产物做多会话对比。
 *
 * <p>仅在显式运行本命令时绑定端口；进程阻塞直至被中断（Ctrl-C）。默认只监听本地回环，产物只读。
 */
@Command(name = "serve", description = "启动本地 Web dashboard 浏览/对比 run 产物")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--log-dir", description = "会话根目录", defaultValue = "log")
    private Path logDir;

    @Option(names = "--port", description = "监听端口（0 为随机）", defaultValue = "8080")
    private int port;

    @Option(names = "--host", description = "绑定地址", defaultValue = "127.0.0.1")
    private String host;

    @Override
    public Integer call() {
        Path root = Path.of(System.getProperty("user.dir")).resolve(logDir).normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("日志目录不存在: " + root);
            return 2;
        }
        try (DashboardServer server = new DashboardServer(root, host, port)) {
            server.start();
            System.out.println("Dashboard: http://" + host + ":" + server.port() + "/  (log-dir=" + root + ")");
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            Thread.currentThread().join();
            return 0;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (java.io.IOException exception) {
            System.err.println("启动失败: " + exception.getMessage());
            return 2;
        }
    }
}
```

`BenchmarkCommand.java`（第 27 行 `subcommands` 修改）：

```java
        subcommands = {EvaluationCommand.class, ServeCommand.class})
```

- [ ] **Step 4: 运行验证通过**

Run: `mvn -q -pl ecbench-app -am -Dtest=DashboardServerTest test`
Expected: PASS（6 个测试）。

- [ ] **Step 5: 提交**

```bash
mvn -q spotless:apply
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/cli/BenchmarkCommand.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java
git commit -m "feat(app): wire serve subcommand with ApiRouter and DashboardServer"
```

---

## Task 6: 全量质量门 + 真实产物手工演示

**Files:**
- 无新增代码；验证 + 文档补充。

- [ ] **Step 1: 全量门禁**

Run: `mvn -q spotless:apply && mvn clean verify`
Expected: 8 模块 BUILD SUCCESS、0 Checkstyle violations、含本特性全部测试通过（不加任何 skip）。

- [ ] **Step 2: 用真实演示产物起服务并手工核对**

```bash
# 复用先前真实 Qwen run 的产物目录（log/20261010_173016_qwen3.7-plus 等）
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080 --host 127.0.0.1
# 浏览器打开 http://127.0.0.1:8080/ 核对：会话列表 / 指标对比表 / 七轴雷达+排名 / 余额曲线 / 单 run 下钻（analysis+chatbox）
# Ctrl-C 停止
```
Expected: 四个视图正常渲染，数据来自真实产物；非法 `{name}` 返回 400；不存在 run 返回 404。

- [ ] **Step 3: 在 README 追加 serve 用法小节**

`README.md` 的「评估子命令」后新增：

```markdown
### 本地 Web 看板（serve）

java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080 --host 127.0.0.1
# 浏览器打开 http://127.0.0.1:8080/ 做多 run 对比（指标表/雷达+排名/余额叠加/单 run 下钻）；Ctrl-C 停止。
```

- [ ] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs: add serve web dashboard usage"
```

---

## Self-Review

**1. Spec coverage：**
- §3/§4 boot + CLI 契约 → Task 5（ServeCommand/DashboardServer/BenchmarkCommand 注册）。✅
- §5 会话发现 → Task 1（SessionScanner）。✅
- §6 五个接口：`/api/sessions`（T1+T5）、`/api/compare`（T3+T5）、`balance`/`analysis`/`chatbox`（T2+T5）、静态（T4）。✅
- §7 前端四视图 + 下钻 → Task 4（app.js）。✅
- §8 路径穿越/只读/绑定/异常映射 → T1（校验）+ T5（400/404/405/500 映射、GET-only、127.0.0.1 默认）。✅
- §9 测试计划 → 每任务含单元/集成测试。✅
- §10 质量门 → 每步 `spotless:apply`，Task 6 全量 `mvn clean verify`。✅
- §13 DoD → Task 6 Step1/2 覆盖。✅

**2. Placeholder scan：** 无占位/TODO。后端各步为完整代码；`OutputStream` 导入由 `ApiRouter.write()` 实际使用，`StaticAssets.respond()` 同样使用，无需多余占位方法。前端 `app.js` 为完整实现（会话选择→compare→表格/雷达/排名→下钻→余额叠加）。

**3. Type consistency：** `SessionInfo.runCount`（record 组件）在 `/api/sessions` 输出键为 `runs`（与 §6 表一致）；`CompareService.compare(List<Path>)`、`RunArtifactService.{analysis,balance,chatbox}(Path,int)`、`DashboardServer(Path,String,int)`、`ApiRouter` 构造参数在各任务间签名一致。`RankingTable.RankedModel.primaryRaw()`、`ModelProfile.normalizedValues()`、`MetricStat.{mean,std,n,fallback}`、`BalancePoint` 字段名均与既有代码一致。✅
