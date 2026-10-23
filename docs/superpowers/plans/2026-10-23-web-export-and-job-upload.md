# Web 导出产物与 job 文件上传 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Web 看板能下载 PNG 图（余额曲线 / 七轴雷达）、排名 CSV/JSON、指标矩阵 CSV，并能上传预构建 job JSONL 内容来跑基准。

**Architecture:** 导出走新增的 `app/web/ExportService`，它编排 evaluation 模块的现成渲染类（`BalancePlotter`/`RadarPlotter`/`RankingTable`）与一个新写的 `ComparisonCsvWriter`，由 `ApiRouter` 的 `/api/export/*` 分支以 `Content-Disposition: attachment` 返回字节。上传走既有 `POST /api/jobs` 的新可选字段 `jobFileContent`：服务端把内容写成自己生成的临时文件，再沿既有 `ConfigurationMerger` → `RunComponentFactory` 链路装配，domain 零改动。

**Tech Stack:** Java 17、JDK `com.sun.net.httpserver`、XChart 4.0.4（`BitmapEncoder.getBitmapBytes`）、commons-csv 1.14.1、Jackson、JUnit 5 + AssertJ、原生 JS（零依赖、自绘 SVG）。

**Spec:** `docs/superpowers/specs/2026-10-23-web-export-and-job-upload-design.md`

## Global Constraints

以下为 spec 的项目级要求，**每个任务都隐含受其约束**：

- 门禁只用两条命令：`mvn -o clean test`（全绿）与 `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`（0 违规）。
- **禁止 `mvn clean verify`**：spotless 绑在 verify 阶段，根 pom 的 `<googleJavaFormat/>` 未设 `<style>AOSP</style>`，与仓库既有 4 空格模块冲突，evaluation 模块即报 20+ 存量违规。
- **禁止 `mvn spotless:apply`**：会重排整模块既有文件、产生巨量无关 diff。
- Checkstyle 生效规则：AvoidStarImport、FileTabCharacter、LineLength ≤ 120、命名类规则、MethodLength ≤ 150。含中文的行按**字符数**量行宽，不要按字节数（会虚高约 3 倍而误判）。
- 缩进：`ecbench-evaluation` / `ecbench-app` 为 4 空格；`ecbench-app/src/main/resources/web/` 前端三件套为 2 空格。
- **不得改动 `RunComparator.METRIC_DEFS`**：那是与 Python `compare_runs` 对齐的冻结契约，加行会打破 `PythonCompatibilityTest` / `AnalysisGoldenParityTest`。
- **不得改动任何既有端点的 JSON 形状**，也不得改动 CLI 命令的行为与输出。
- 下载文件名一律为固定 ASCII 常量（`balance.png`/`radar.png`/`ranking.csv`/`ranking.json`/`matrix.csv`），**绝不把会话名、模型键或 `title` 拼进 `Content-Disposition`**（CRLF 头注入面）。
- 请求体上限保持 `MAX_BODY_BYTES = 65_536` 不变。
- HTTP 不接受任何**路径**类参数；上传的是 job 文件**内容**，临时文件路径由服务端生成。
- 提交时只 `git add` 本任务涉及的文件，**绝不 `git add -A`**（工作树有大量无关未提交改动）。

---

### Task 1: BalancePlotter 抽出 buildChart 并新增 png()

**Files:**
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/BalancePlotter.java:53-91`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/BalancePlotterTest.java`

**Interfaces:**
- Consumes: 既有 `BalanceCsvReader.read(Path) → List<BalancePoint>`、`BalancePoint.totalBalance()`。
- Produces: `public byte[] BalancePlotter.png(List<Path> csvFiles, List<String> labels, String title)` — Task 6 的 `ExportService` 依赖此签名。`labels.size()` 必须等于 `csvFiles.size()`。

- [ ] **Step 1: 写失败测试**

在 `BalancePlotterTest` 的 import 段加入 `assertThatThrownBy`（该文件当前只 import 了 `assertThat`）：

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

在类末尾（现有 `writesValidPngAndSummarizesFinalBalances` 之后）追加两个用例：

```java
    @Test
    void pngReturnsValidPngBytesWithoutTouchingDisk(@TempDir Path tempDir) throws Exception {
        Path csv0 = writeBalance(tempDir, "run_0", 1000, 1200, 1500);
        Path csv1 = writeBalance(tempDir, "run_1", 1000, 900, 1100);
        BalancePlotter plotter = new BalancePlotter();

        byte[] png =
                plotter.png(
                        List.of(csv0, csv1),
                        List.of("2026_a/run_0", "2026_b/run_0"),
                        "t");

        assertThat(png.length).isGreaterThan(0);
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1] & 0xFF).isEqualTo(0x50);
        assertThat(png[2] & 0xFF).isEqualTo(0x4E);
        assertThat(png[3] & 0xFF).isEqualTo(0x47);
        // 不落盘：临时目录里只应有两个 CSV，没有任何 PNG
        assertThat(Files.list(tempDir).filter(p -> p.toString().endsWith(".png")).count())
                .isZero();
    }

    @Test
    void pngRejectsMismatchedLabelsAndEmptyInput(@TempDir Path tempDir) throws Exception {
        Path csv0 = writeBalance(tempDir, "run_0", 1000, 1200);
        BalancePlotter plotter = new BalancePlotter();

        assertThatThrownBy(() -> plotter.png(List.of(csv0), List.of("a", "b"), "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不一致");
        assertThatThrownBy(() -> plotter.png(List.of(), List.of(), "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可绘制的余额 CSV");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-evaluation test -Dtest=BalancePlotterTest`
Expected: 编译失败，报 `找不到符号: 方法 png(java.util.List<java.nio.file.Path>,java.util.List<java.lang.String>,java.lang.String)`

- [ ] **Step 3: 实现——抽出 buildChart 并加 png()**

把 `BalancePlotter.java` 的 `plot` 方法（第 53-91 行）整体替换为下面三个方法。**`plot` 的对外行为逐字不变**（同样的 chart 尺寸、轴标题、默认标题、剥 `.png` 后缀逻辑、同样的异常文案）：

```java
    public Path plot(List<Path> csvFiles, PlotOptions options) {
        List<String> labels = new ArrayList<>();
        for (Path csv : csvFiles) {
            labels.add(seriesName(csv));
        }
        XYChart chart = buildChart(csvFiles, labels, options.title());
        try {
            Path parent = options.output().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // XChart 的 saveBitmap 接收不含扩展名的文件名并自行追加 ".png"，故先剥去 .png 再传入。
            String fileName = options.output().toString();
            String base =
                    fileName.toLowerCase().endsWith(".png")
                            ? fileName.substring(0, fileName.length() - 4)
                            : fileName;
            BitmapEncoder.saveBitmap(chart, base, BitmapFormat.PNG);
            return Path.of(base + ".png");
        } catch (IOException exception) {
            throw new UncheckedIOException("无法写出余额图: " + options.output(), exception);
        }
    }

    /**
     * 把余额曲线直接渲染为 PNG 字节，<b>不落盘</b>；供 HTTP 下载端点使用。
     *
     * @param csvFiles 每条曲线一个余额 CSV
     * @param labels 每条曲线的图例名，长度必须与 {@code csvFiles} 相同。多会话叠加时必须带会话前缀，
     *     否则各会话的 {@code run_0} 会同名而在图例里分不清
     * @param title 图标题，{@code null} 时用 {@code "daily balance"}
     */
    public byte[] png(List<Path> csvFiles, List<String> labels, String title) {
        XYChart chart = buildChart(csvFiles, labels, title);
        try {
            return BitmapEncoder.getBitmapBytes(chart, BitmapFormat.PNG);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法编码余额图 PNG", exception);
        }
    }

    /**
     * 构建 XYChart：每条 CSV 一条曲线，横轴为日序号，纵轴为权威列 total_balance。
     *
     * @throws IllegalArgumentException 无 CSV 可绘，或图例名数量与 CSV 数量不一致
     */
    private XYChart buildChart(List<Path> csvFiles, List<String> labels, String title) {
        if (csvFiles.isEmpty()) {
            throw new IllegalArgumentException("没有可绘制的余额 CSV");
        }
        if (labels.size() != csvFiles.size()) {
            throw new IllegalArgumentException(
                    "图例名数量 " + labels.size() + " 与 CSV 数量 " + csvFiles.size() + " 不一致");
        }
        XYChart chart =
                new XYChartBuilder()
                        .width(1200)
                        .height(600)
                        .title(title != null ? title : "daily balance")
                        .xAxisTitle("day")
                        .yAxisTitle("total_balance (bank + wallet + escrow)")
                        .build();
        for (int i = 0; i < csvFiles.size(); i++) {
            List<BalancePoint> points = reader.read(csvFiles.get(i));
            List<Double> x = new ArrayList<>();
            List<Double> y = new ArrayList<>();
            for (int day = 0; day < points.size(); day++) {
                x.add((double) day);
                y.add(points.get(day).totalBalance());
            }
            chart.addSeries(labels.get(i), x, y);
        }
        return chart;
    }
```

- [ ] **Step 4: 跑测试确认通过（含既有用例）**

Run: `mvn -o -pl ecbench-evaluation test -Dtest=BalancePlotterTest`
Expected: PASS，3 个用例全绿。既有的 `writesValidPngAndSummarizesFinalBalances` **必须一字不改地通过**——它是 `plot()` 行为不变的回归锚。

- [ ] **Step 5: 风格门**

Run: `mvn -o -pl ecbench-evaluation checkstyle:check`
Expected: BUILD SUCCESS，0 违规。

- [ ] **Step 6: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/BalancePlotter.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/BalancePlotterTest.java
git commit -m "feat(evaluation): render balance chart to PNG bytes without touching disk"
```

---

### Task 2: RadarPlotter 抽出 buildChart 并新增 png()

**Files:**
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RadarPlotter.java:50-90`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/RadarPlotterTest.java`

**Interfaces:**
- Consumes: 既有 `NormalizedProfile.models()` / `medianNormalized()`、`ProfileAxis.values()` / `label()`。
- Produces: `public byte[] RadarPlotter.png(NormalizedProfile profile, String title)` — Task 6 依赖此签名。`profile.models()` 为空时抛 `IllegalArgumentException("没有可绘制的模型画像")`。

- [ ] **Step 1: 先看既有测试怎么造 NormalizedProfile**

Run: `sed -n '1,60p' ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/RadarPlotterTest.java`

新用例**必须复用该文件已有的 fixture 构造方式**（不要另创一套），把它抽成局部变量或直接照抄构造表达式。

- [ ] **Step 2: 写失败测试**

在 `RadarPlotterTest` 里追加（`profileFixture()` 处替换为 Step 1 看到的实际构造表达式）：

```java
    @Test
    void pngReturnsValidPngBytesWithoutTouchingDisk(@TempDir Path tempDir) throws Exception {
        NormalizedProfile profile = profileFixture();

        byte[] png = new RadarPlotter().png(profile, "t");

        assertThat(png.length).isGreaterThan(0);
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1] & 0xFF).isEqualTo(0x50);
        assertThat(png[2] & 0xFF).isEqualTo(0x4E);
        assertThat(png[3] & 0xFF).isEqualTo(0x47);
        assertThat(Files.list(tempDir).count()).isZero();
    }

    @Test
    void pngRejectsEmptyProfile() {
        NormalizedProfile empty =
                new NormalizedProfile(List.of(), new double[ProfileAxis.values().length]);

        assertThatThrownBy(() -> new RadarPlotter().png(empty, "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可绘制的模型画像");
    }
```

若 `NormalizedProfile` 的实际构造签名与上面不同，**以 Step 1 读到的真实签名为准**调整 `empty` 的构造；断言的异常类型与文案不变。
需要时补 import：`assertThatThrownBy`、`java.nio.file.Files`、`org.junit.jupiter.api.io.TempDir`。

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -o -pl ecbench-evaluation test -Dtest=RadarPlotterTest`
Expected: 编译失败，报 `找不到符号: 方法 png(...)`

- [ ] **Step 4: 实现——抽出 buildChart 并加 png()**

把 `RadarPlotter.java` 的 `plot` 方法（第 50-90 行）整体替换为下面三个方法。`plot` 的对外行为逐字不变：

```java
    public Path plot(NormalizedProfile profile, RadarOptions options) {
        RadarChart chart = buildChart(profile, options.title());
        try {
            Path parent = options.output().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // XChart 的 saveBitmap 接收不含扩展名的文件名并自行追加 ".png"，故先剥去 .png 再传入。
            String fileName = options.output().toString();
            String base =
                    fileName.toLowerCase().endsWith(".png")
                            ? fileName.substring(0, fileName.length() - 4)
                            : fileName;
            BitmapEncoder.saveBitmap(chart, base, BitmapFormat.PNG);
            return Path.of(base + ".png");
        } catch (IOException exception) {
            throw new UncheckedIOException("无法写出雷达图: " + options.output(), exception);
        }
    }

    /**
     * 把七轴画像直接渲染为 PNG 字节，<b>不落盘</b>；供 HTTP 下载端点使用。
     *
     * @param title 图标题，{@code null} 时用 {@code "model capability profile"}
     * @throws IllegalArgumentException 没有可绘制的模型画像
     */
    public byte[] png(NormalizedProfile profile, String title) {
        RadarChart chart = buildChart(profile, title);
        try {
            return BitmapEncoder.getBitmapBytes(chart, BitmapFormat.PNG);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法编码雷达图 PNG", exception);
        }
    }

    /** 构建 RadarChart：每模型一条多边形（NaN 回退 0），另叠加一条中位数虚线。 */
    private RadarChart buildChart(NormalizedProfile profile, String title) {
        if (profile.models().isEmpty()) {
            throw new IllegalArgumentException("没有可绘制的模型画像");
        }
        ProfileAxis[] axes = ProfileAxis.values();
        String[] labels = new String[axes.length];
        for (int i = 0; i < axes.length; i++) {
            labels[i] = axes[i].label();
        }

        RadarChart chart =
                new RadarChartBuilder()
                        .width(900)
                        .height(900)
                        .title(title != null ? title : "model capability profile")
                        .build();
        chart.setRadiiLabels(labels);

        for (ModelProfile model : profile.models()) {
            chart.addSeries(model.name(), toValues(model.normalizedValues()));
        }
        RadarSeries median = chart.addSeries("median", toValues(profile.medianNormalized()));
        median.setLineStyle(DASHED);
        median.setLineColor(Color.DARK_GRAY);
        return chart;
    }
```

- [ ] **Step 5: 跑测试确认通过（含既有用例）**

Run: `mvn -o -pl ecbench-evaluation test -Dtest=RadarPlotterTest`
Expected: PASS。既有 `plot()` 用例必须一字不改地通过。

- [ ] **Step 6: 风格门 + 提交**

```bash
mvn -o -pl ecbench-evaluation checkstyle:check
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RadarPlotter.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/RadarPlotterTest.java
git commit -m "feat(evaluation): render radar chart to PNG bytes without touching disk"
```

---

### Task 3: ComparisonCsvWriter（长表 tidy 指标矩阵 CSV）

**Files:**
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/ComparisonCsvWriter.java`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/ComparisonCsvWriterTest.java`（新建）

**Interfaces:**
- Consumes: `ComparisonReport(List<SessionComparison> sessions)`、`SessionComparison(String name, int runCount, List<MetricStat> metrics)`（三参重载）、`MetricStat(String label, Double mean, Double std, int n, String fallback)`。
- Produces: `public String ComparisonCsvWriter.toLongCsv(ComparisonReport report)` — Task 6 依赖此签名。表头逐字为 `session,metric,mean,std,n`。

- [ ] **Step 1: 写失败测试**

新建 `ComparisonCsvWriterTest.java`：

```java
package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ComparisonCsvWriter 测试：长表（tidy）形状、缺失均值的空格处理、含逗号标签的引号转义。
 */
class ComparisonCsvWriterTest {

    private static ComparisonReport report() {
        SessionComparison a =
                new SessionComparison(
                        "2026_a",
                        2,
                        List.of(
                                new MetricStat("final_balance", 95000.0, 1200.5, 2, "-"),
                                new MetricStat("bankrupt", null, null, 0, "n/a")));
        SessionComparison b =
                new SessionComparison(
                        "2026_b", 1, List.of(new MetricStat("SE+, strong", 0.25, null, 1, "-")));
        return new ComparisonReport(List.of(a, b));
    }

    /** 按行切分并丢掉尾部空行，便于逐行断言（commons-csv 用 CRLF 结尾）。 */
    private static List<String> lines(String csv) {
        return List.of(csv.split("\r\n|\n|\r")).stream().filter(line -> !line.isEmpty()).toList();
    }

    @Test
    void writesTidyRowsInSessionThenMetricOrder() {
        List<String> rows = lines(new ComparisonCsvWriter().toLongCsv(report()));

        assertThat(rows.get(0)).isEqualTo("session,metric,mean,std,n");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(1)).isEqualTo("2026_a,final_balance,95000.000000,1200.500000,2");
        assertThat(rows.get(3)).startsWith("2026_b,");
    }

    @Test
    void leavesMeanAndStdEmptyWhenMeanMissingButKeepsN() {
        List<String> rows = lines(new ComparisonCsvWriter().toLongCsv(report()));

        assertThat(rows.get(2)).isEqualTo("2026_a,bankrupt,,,0");
    }

    @Test
    void quotesLabelsContainingCommas() {
        List<String> rows = lines(new ComparisonCsvWriter().toLongCsv(report()));

        assertThat(rows.get(3)).isEqualTo("2026_b,\"SE+, strong\",0.250000,,1");
    }

    @Test
    void emptyReportYieldsHeaderOnly() {
        String csv = new ComparisonCsvWriter().toLongCsv(new ComparisonReport(List.of()));

        assertThat(lines(csv)).containsExactly("session,metric,mean,std,n");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-evaluation test -Dtest=ComparisonCsvWriterTest`
Expected: 编译失败，报 `找不到符号: 类 ComparisonCsvWriter`

- [ ] **Step 3: 实现**

新建 `ComparisonCsvWriter.java`：

```java
package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.MetricStat;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * 把 {@link ComparisonReport} 渲染为长表（tidy）CSV，供看板导出后做二次分析。
 *
 * <p>一行一个 (会话, 指标) 对，列为 {@code session,metric,mean,std,n}。刻意不含 model 列：
 * {@link SessionComparison} 没有该字段，而「会话名最后一个下划线段即模型键」是 app 层的命名约定，
 * 下沉到本模块属错误分层。{@code mean} 为 null 或 NaN 时 mean/std 两格留空而 n 照出，
 * 使两列保持纯数值；{@code MetricStat.fallback} 是展示占位，有意不导出。
 *
 * <p>只读 {@link ComparisonReport}，<b>绝不触碰 {@code RunComparator.METRIC_DEFS}</b>——那是与
 * Python {@code compare_runs} 对齐的冻结契约，加行会打破兼容性测试。引号转义交给 commons-csv。
 */
public final class ComparisonCsvWriter {

    /** 数值格式：比看板 UI 的 4 位更高精度，便于二次分析。 */
    private static final String NUMBER = "%.6f";

    private static final String[] HEADER = {"session", "metric", "mean", "std", "n"};

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setHeader(HEADER).get();

    public String toLongCsv(ComparisonReport report) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, FORMAT)) {
            for (SessionComparison session : report.sessions()) {
                for (MetricStat stat : session.metrics()) {
                    printer.print(session.name());
                    printer.print(stat.label());
                    printer.print(number(stat.mean()));
                    printer.print(number(stat.std()));
                    printer.print(stat.n());
                    printer.println();
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法渲染指标矩阵 CSV", exception);
        }
        return out.toString();
    }

    /** null 与 NaN 都渲染为空单元格（而不是 n/a），保证 mean/std 是纯数值列。 */
    private static String number(Double value) {
        if (value == null || Double.isNaN(value)) {
            return "";
        }
        return String.format(NUMBER, value);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-evaluation test -Dtest=ComparisonCsvWriterTest`
Expected: PASS，4 个用例全绿。

- [ ] **Step 5: 确认没碰到冻结契约**

Run: `git diff --stat ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RunComparator.java`
Expected: 无输出（`RunComparator` 一行未改）。

- [ ] **Step 6: 风格门 + 提交**

```bash
mvn -o -pl ecbench-evaluation checkstyle:check
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/ComparisonCsvWriter.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/ComparisonCsvWriterTest.java
git commit -m "feat(evaluation): add tidy long-format CSV writer for comparison matrix"
```

---

### Task 4: HttpResponses 共享响应器 + StaticAssets 委托

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/HttpResponses.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/StaticAssets.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/StaticAssetsTest.java`（既有，作为回归锚，**不新增用例**）

**Interfaces:**
- Produces: `static void HttpResponses.respond(HttpExchange, int status, String contentType, byte[] body)` 与 `static void HttpResponses.attachment(HttpExchange, String contentType, String filename, byte[] body)`（包级可见，二者均 `throws IOException`）— Task 7 的 `ApiRouter` 依赖这两个签名。

- [ ] **Step 1: 先跑既有测试建立基线**

Run: `mvn -o -pl ecbench-app test -Dtest=StaticAssetsTest`
Expected: PASS。记下用例数——重构后必须完全一致。

- [ ] **Step 2: 新建 HttpResponses**

```java
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
```

- [ ] **Step 3: StaticAssets 改为委托**

删掉 `StaticAssets` 里的私有 `respond` 方法，并把三处调用改为 `HttpResponses.respond(...)`。改完后 `handle` 与两个私有辅助方法如下（`resolve` 与 `contentType` **一字不改**）：

```java
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String resource = resolve(exchange.getRequestURI().getPath());
        if (resource == null) {
            HttpResponses.respond(exchange, 404, "text/plain; charset=utf-8", bytes("not found"));
            return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                HttpResponses.respond(
                        exchange, 404, "text/plain; charset=utf-8", bytes("missing asset"));
                return;
            }
            HttpResponses.respond(exchange, 200, contentType(resource), in.readAllBytes());
        }
    }
```

同时删掉 `StaticAssets` 顶部已不再使用的 `import java.io.OutputStream;`（Checkstyle 无 UnusedImports 规则，门禁不会拦，需自查）。

- [ ] **Step 4: 跑回归锚**

Run: `mvn -o -pl ecbench-app test -Dtest=StaticAssetsTest`
Expected: PASS，用例数与 Step 1 完全一致。**这是纯重构，任何失败都说明行为被改动了。**

- [ ] **Step 5: 风格门 + 提交**

```bash
mvn -o -pl ecbench-app checkstyle:check
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/HttpResponses.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/StaticAssets.java
git commit -m "refactor(web): extract shared HTTP responder for static assets and downloads"
```

---

### Task 5: SessionScanner.runIndexes 提公开 + CompareService.compareAll 抽取

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/SessionScanner.java:83`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/CompareService.java:49-59`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/SessionScannerTest.java`、`CompareServiceTest.java`

**Interfaces:**
- Produces:
  - `public List<Integer> SessionScanner.runIndexes(Path dir)` — 升序 run 序号，来自 `metrics/run_<i>_analysis.json`；`metrics/` 缺失时返回空列表。
  - `public CompareService.Compared CompareService.compareAll(List<Path> sessionDirs)`，其中 `public record CompareService.Compared(ComparisonReport report, NormalizedProfile profile)`。
- Task 6 的 `ExportService` 同时依赖这两个签名。

- [ ] **Step 1: 写失败测试（runIndexes 公开）**

在 `SessionScannerTest` 追加（沿用该文件已有的建目录风格）：

```java
    @Test
    void runIndexesReturnsAscendingIndexesAndEmptyWhenMetricsMissing(@TempDir Path log)
            throws Exception {
        Path session = log.resolve("2026_x");
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(session.resolve("metrics/run_1_analysis.json"), "{}");
        Files.writeString(session.resolve("metrics/run_0_analysis.json"), "{}");
        Files.writeString(session.resolve("metrics/not_a_run.json"), "{}");
        SessionScanner scanner = new SessionScanner(log);

        assertThat(scanner.runIndexes(session)).containsExactly(0, 1);
        assertThat(scanner.runIndexes(log.resolve("2026_absent"))).isEmpty();
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=SessionScannerTest`
Expected: 编译失败，报 `runIndexes 在 SessionScanner 中不是 public`（或「找不到符号」）。

- [ ] **Step 3: 实现——提为 public**

把 `SessionScanner` 里的

```java
    /** 从 {@code metrics/} 子目录收集 run 序号并升序返回。 */
    private List<Integer> runIndexes(Path dir) {
```

改为

```java
    /**
     * 从 {@code metrics/} 子目录收集 run 序号并升序返回；目录缺失时返回空列表。
     *
     * <p>公开供导出端点枚举全部 run。<b>注意</b>：序号来源是 {@code metrics/}，而余额 CSV 在
     * {@code balance/} 下，两者可能不同步（例如某个 run 中途失败），因此调用方仍须逐个
     * {@code Files.isRegularFile} 过滤，不能只信序号列表或 {@code SessionInfo.runCount()}。
     */
    public List<Integer> runIndexes(Path dir) {
```

方法体一字不改。

- [ ] **Step 4: 写失败测试（compareAll）**

在 `CompareServiceTest` 追加（沿用该文件已有的 fixture 构造方式）：

```java
    @Test
    void compareAllExposesReportAndProfileFromTheSamePipeline(@TempDir Path log) throws Exception {
        Path session = log.resolve("2026_qwen");
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(
                session.resolve("metrics/run_0_analysis.json"),
                "{\"profitability\":{\"final_balance\":95000.0,\"initial_balance\":100000.0,"
                        + "\"peak_total_assets\":100000.0,\"peak_drawdown\":0.0,"
                        + "\"stores_opened\":1,\"store_reopens\":0},"
                        + "\"operational_efficiency\":{\"total_tool_calls\":8}}");
        CompareService service = new CompareService(new ObjectMapper());

        CompareService.Compared compared = service.compareAll(List.of(session));

        assertThat(compared.report().sessions()).hasSize(1);
        assertThat(compared.report().sessions().get(0).name()).isEqualTo("2026_qwen");
        assertThat(compared.profile().models()).hasSize(1);
        // compare() 的 JSON 与 compareAll() 同源：radar 段的模型名与 profile 一致
        assertThat(service.compare(List.of(session)).toString())
                .contains("2026_qwen")
                .contains("\"radar\"");
    }
```

- [ ] **Step 5: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=CompareServiceTest`
Expected: 编译失败，报 `找不到符号: 方法 compareAll`

- [ ] **Step 6: 实现——抽出 compareAll**

在 `CompareService` 中，把现有 `compare(List<Path>)` 的前两行替换为对 `compareAll` 的调用，并新增 record 与方法。改完后：

```java
    /** {@link #compare} 与导出端点共用的计算结果：聚合报告 + 归一化画像。 */
    public record Compared(ComparisonReport report, NormalizedProfile profile) {
    }

    /**
     * 从会话目录算出聚合报告与七轴归一化画像。
     *
     * <p>抽成公开方法，使导出端点（雷达 PNG、排名 CSV/JSON、指标矩阵 CSV）与 {@code /api/compare}
     * 的 {@code radar}/{@code ranking} 段共用同一条计算链，避免两处口径漂移。返回两者是为了让调用方
     * 只算一次——{@code compare} 与矩阵导出都要 report，雷达与排名都要 profile。
     */
    public Compared compareAll(List<Path> sessionDirs) {
        ComparisonReport report = new RunComparator().compare(sessionDirs);
        return new Compared(report, new CrossModelProfiler().profile(report));
    }

    /**
     * 对给定会话目录依次输出聚合指标、归一化雷达、排名，以及论文 §4.3 的维度视图四段；空输入返回同形的空结构。
     */
    public JsonNode compare(List<Path> sessionDirs) {
        Compared compared = compareAll(sessionDirs);
        ComparisonReport report = compared.report();
        NormalizedProfile profile = compared.profile();
        ObjectNode root = mapper.createObjectNode();
        root.set("sessions", sessionsNode(report));
        root.set("radar", radarNode(profile));
        root.set("ranking", rankingNode(new RankingTable().rank(profile)));
        ObjectNode paper = dimensions.node(sessionDirs, report);
        paper.fieldNames().forEachRemaining(name -> root.set(name, paper.get(name)));
        return root;
    }
```

`compare` 方法体除前三行外**一字不改**，JSON 输出形状零变化。

- [ ] **Step 7: 跑两个测试类 + 全模块**

Run: `mvn -o -pl ecbench-app test -Dtest='SessionScannerTest,CompareServiceTest'`
Expected: PASS。

Run: `mvn -o -pl ecbench-app test`
Expected: BUILD SUCCESS，既有用例零失败（`CompareServiceTest` 的既有 JSON 断言是本次重构的回归锚）。

- [ ] **Step 8: 风格门 + 提交**

```bash
mvn -o -pl ecbench-app checkstyle:check
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/SessionScanner.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/CompareService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/SessionScannerTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/CompareServiceTest.java
git commit -m "refactor(web): expose run indexes and share compare pipeline with exports"
```

---

### Task 6: ExportService

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ExportService.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/ExportServiceTest.java`（新建）

**Interfaces:**
- Consumes: `SessionScanner.sessionDir(String)`、`SessionScanner.runIndexes(Path)`、`CompareService.compareAll(List<Path>)` → `Compared(report, profile)`、`BalancePlotter.png(List<Path>, List<String>, String)`、`RadarPlotter.png(NormalizedProfile, String)`、`RankingTable.toCsv(NormalizedProfile)`、`RankingTable.toJson(NormalizedProfile, ObjectMapper)`、`ComparisonCsvWriter.toLongCsv(ComparisonReport)`、`RunLayoutResolver.resolve(Path).balanceCsv(int)`。
- Produces: `public record ExportService.Export(String contentType, String filename, byte[] body)`，以及五个方法 `balancePng(List<String> sessions, String title)`、`radarPng(List<String> sessions, String title)`、`rankingCsv(List<String> sessions)`、`rankingJson(List<String> sessions)`、`matrixCsv(List<String> sessions)`，全部返回 `Export`。Task 7 依赖这些签名。

- [ ] **Step 1: 写失败测试**

新建 `ExportServiceTest.java`：

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.web.ExportService.Export;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ExportService 测试：锁定两条不变量——① 会话名解析必须发生在绘图之前且在 try 之外，
 * 使非法名的 IllegalArgumentException(400) 不被误转译成 404；② 下载文件名是固定 ASCII 常量。
 */
class ExportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ANALYSIS =
            "{\"profitability\":{\"final_balance\":95000.0,\"initial_balance\":100000.0,"
                    + "\"peak_total_assets\":100000.0,\"peak_drawdown\":0.0,"
                    + "\"stores_opened\":1,\"store_reopens\":0},"
                    + "\"operational_efficiency\":{\"total_tool_calls\":8}}";

    private static final String BALANCE_HEADER =
            "date,bank_balance,platform_wallet,total_balance,open_stores,warehouse_items,"
                    + "storage_charged\n";

    @TempDir Path log;

    private ExportService exports;

    /** 建两个会话，各含 run_0 的 analysis.json 与三行余额 CSV。 */
    @BeforeEach
    void writeSessions() throws Exception {
        for (String name : List.of("2026_a", "2026_b")) {
            Path session = log.resolve(name);
            Files.createDirectories(session.resolve("metrics"));
            Files.writeString(session.resolve("metrics/run_0_analysis.json"), ANALYSIS);
            Files.createDirectories(session.resolve("balance"));
            Files.writeString(
                    session.resolve("balance/run_0_daily_balance.csv"),
                    BALANCE_HEADER
                            + "2026-01-01,0,0,1000.00,0,0,0\n"
                            + "2026-01-02,0,0,1200.00,0,0,0\n"
                            + "2026-01-03,0,0,1500.00,0,0,0\n");
        }
        SessionScanner scanner = new SessionScanner(log);
        CompareService compare = new CompareService(MAPPER);
        exports = new ExportService(MAPPER, scanner, compare);
    }

    @Test
    void balancePngUsesSessionQualifiedSeriesLabelsAndFixedFilename() {
        Export export = exports.balancePng(List.of("2026_a", "2026_b"), "t");

        assertThat(export.contentType()).isEqualTo("image/png");
        assertThat(export.filename()).isEqualTo("balance.png");
        assertThat(export.body().length).isGreaterThan(0);
        assertThat(export.body()[0] & 0xFF).isEqualTo(0x89);
        assertThat(export.body()[1] & 0xFF).isEqualTo(0x50);
    }

    @Test
    void balancePngSkipsRunIndexesWithoutBalanceCsv() throws Exception {
        // metrics/ 里有 run_7 的 analysis.json，但 balance/ 下没有对应 CSV：
        // 序号来自 metrics、文件在 balance，两者不同步时必须靠 isRegularFile 过滤掉。
        Files.writeString(log.resolve("2026_a/metrics/run_7_analysis.json"), ANALYSIS);

        Export export = exports.balancePng(List.of("2026_a"), null);

        assertThat(export.body().length).isGreaterThan(0);
    }

    @Test
    void balancePngWithoutAnyCsvIsNotFound() throws Exception {
        Files.delete(log.resolve("2026_a/balance/run_0_daily_balance.csv"));
        Files.delete(log.resolve("2026_b/balance/run_0_daily_balance.csv"));

        assertThatThrownBy(() -> exports.balancePng(List.of("2026_a", "2026_b"), null))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("没有可绘制的余额 CSV");
    }

    @Test
    void malformedSessionNameStaysBadRequestAndIsNotTranslatedToNotFound() {
        // 锁死转译范围：会话名解析在 try 之外，IllegalArgumentException 必须原样冒泡（→400）
        assertThatThrownBy(() -> exports.balancePng(List.of("bad name"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exports.radarPng(List.of("bad name"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exports.matrixCsv(List.of("../escape")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absentSessionIsNotFound() {
        assertThatThrownBy(() -> exports.radarPng(List.of("2026_absent"), null))
                .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void radarPngHasFixedFilenameAndPngMagic() {
        Export export = exports.radarPng(List.of("2026_a", "2026_b"), null);

        assertThat(export.filename()).isEqualTo("radar.png");
        assertThat(export.contentType()).isEqualTo("image/png");
        assertThat(export.body()[0] & 0xFF).isEqualTo(0x89);
    }

    @Test
    void rankingCsvAndJsonCarryFixedFilenamesAndContentTypes() {
        Export csv = exports.rankingCsv(List.of("2026_a", "2026_b"));
        Export json = exports.rankingJson(List.of("2026_a", "2026_b"));

        assertThat(csv.filename()).isEqualTo("ranking.csv");
        assertThat(csv.contentType()).isEqualTo("text/csv; charset=utf-8");
        assertThat(new String(csv.body(), StandardCharsets.UTF_8))
                .startsWith("rank,model,primary_raw");
        assertThat(json.filename()).isEqualTo("ranking.json");
        assertThat(json.contentType()).isEqualTo("application/json; charset=utf-8");
        assertThat(new String(json.body(), StandardCharsets.UTF_8)).contains("\"models\"");
    }

    @Test
    void matrixCsvHasTidyHeaderAndOneRowPerSessionMetric() {
        Export export = exports.matrixCsv(List.of("2026_a", "2026_b"));
        String csv = new String(export.body(), StandardCharsets.UTF_8);

        assertThat(export.filename()).isEqualTo("matrix.csv");
        assertThat(csv).startsWith("session,metric,mean,std,n");
        assertThat(csv).contains("2026_a,final_balance,").contains("2026_b,final_balance,");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=ExportServiceTest`
Expected: 编译失败，报 `找不到符号: 类 ExportService`

- [ ] **Step 3: 实现 ExportService**

新建 `ExportService.java`：

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.BalancePlotter;
import io.github.ecommercebench.evaluation.ComparisonCsvWriter;
import io.github.ecommercebench.evaluation.RadarPlotter;
import io.github.ecommercebench.evaluation.RankingTable;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver.RunLayout;
import io.github.ecommercebench.evaluation.model.NormalizedProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 看板文件导出的编排：把 evaluation 模块的现成渲染能力包装成可下载的字节。
 *
 * <p>与只读 JSON 端点<b>同源同口径</b>——雷达与排名走 {@link CompareService#compareAll}，余额曲线走
 * {@link BalancePlotter}，指标矩阵走 {@link ComparisonCsvWriter}。本类不自己算任何指标。
 *
 * <p>两条不变量：① 会话名解析必须发生在调用绘图类<b>之前</b>且在其 try 之外，否则非法会话名的
 * {@code IllegalArgumentException}（400）会被误转译成 404；② 下载文件名一律为固定 ASCII 常量，
 * 绝不拼入会话名或 title，杜绝 {@code Content-Disposition} 头注入。
 *
 * <p>余额 PNG 画<b>全部 run</b>：run 序号取自 {@code metrics/}，而余额 CSV 在 {@code balance/} 下，
 * 两者可能不同步，故逐个 {@code isRegularFile} 过滤；图例名带会话前缀，避免多会话的 {@code run_0} 重名。
 */
public final class ExportService {

    /** 一次导出结果：MIME 类型、下载文件名与正文字节。 */
    public record Export(String contentType, String filename, byte[] body) {
    }

    private static final String PNG = "image/png";

    private static final String CSV = "text/csv; charset=utf-8";

    private static final String JSON = "application/json; charset=utf-8";

    private final ObjectMapper mapper;
    private final SessionScanner scanner;
    private final CompareService compare;
    private final RunLayoutResolver resolver;
    private final BalancePlotter balancePlotter = new BalancePlotter();
    private final RadarPlotter radarPlotter = new RadarPlotter();
    private final RankingTable rankingTable = new RankingTable();
    private final ComparisonCsvWriter matrixWriter = new ComparisonCsvWriter();

    public ExportService(ObjectMapper mapper, SessionScanner scanner, CompareService compare) {
        this(mapper, scanner, compare, new RunLayoutResolver());
    }

    public ExportService(
            ObjectMapper mapper,
            SessionScanner scanner,
            CompareService compare,
            RunLayoutResolver resolver) {
        this.mapper = mapper;
        this.scanner = scanner;
        this.compare = compare;
        this.resolver = resolver;
    }

    /**
     * 余额曲线 PNG：所有给定会话的所有已落盘 run 叠加成一张。
     *
     * @throws ArtifactNotFoundException 过滤后没有任何真实存在的余额 CSV
     */
    public Export balancePng(List<String> sessions, String title) {
        List<Path> dirs = resolveAll(sessions);
        List<Path> csvFiles = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Path dir : dirs) {
            RunLayout layout = resolver.resolve(dir);
            for (int run : scanner.runIndexes(dir)) {
                Path csv = layout.balanceCsv(run);
                if (Files.isRegularFile(csv)) {
                    csvFiles.add(csv);
                    labels.add(dir.getFileName() + "/run_" + run);
                }
            }
        }
        if (csvFiles.isEmpty()) {
            throw new ArtifactNotFoundException("没有可绘制的余额 CSV");
        }
        return new Export(PNG, "balance.png", balancePlotter.png(csvFiles, labels, title));
    }

    /** 七轴雷达 PNG；与 {@code /api/compare} 的 radar 段同一条计算链。 */
    public Export radarPng(List<String> sessions, String title) {
        NormalizedProfile profile = compare.compareAll(resolveAll(sessions)).profile();
        byte[] body;
        try {
            body = radarPlotter.png(profile, title);
        } catch (IllegalArgumentException exception) {
            // 请求合法、只是没有可绘的画像 → 404 而非 400
            throw new ArtifactNotFoundException(String.valueOf(exception.getMessage()));
        }
        return new Export(PNG, "radar.png", body);
    }

    /** 排名表 CSV（{@code rank,model,primary_raw,<七轴>}）。 */
    public Export rankingCsv(List<String> sessions) {
        NormalizedProfile profile = compare.compareAll(resolveAll(sessions)).profile();
        return new Export(
                CSV, "ranking.csv", rankingTable.toCsv(profile).getBytes(StandardCharsets.UTF_8));
    }

    /** 排名表 JSON（含 axes / median / models 三段）。 */
    public Export rankingJson(List<String> sessions) {
        NormalizedProfile profile = compare.compareAll(resolveAll(sessions)).profile();
        return new Export(
                JSON,
                "ranking.json",
                rankingTable.toJson(profile, mapper).getBytes(StandardCharsets.UTF_8));
    }

    /** 指标矩阵长表 CSV（{@code session,metric,mean,std,n}）。 */
    public Export matrixCsv(List<String> sessions) {
        CompareService.Compared compared = compare.compareAll(resolveAll(sessions));
        return new Export(
                CSV,
                "matrix.csv",
                matrixWriter.toLongCsv(compared.report()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 逐个把会话名解析为目录路径。
     *
     * <p><b>必须在调用绘图类之前完成且不包在转译 try 里</b>：非法名要抛
     * {@code IllegalArgumentException}（→400），不存在要抛 {@code ArtifactNotFoundException}（→404）。
     */
    private List<Path> resolveAll(List<String> sessions) {
        List<Path> dirs = new ArrayList<>(sessions.size());
        for (String name : sessions) {
            dirs.add(scanner.sessionDir(name));
        }
        return dirs;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=ExportServiceTest`
Expected: PASS，8 个用例全绿。

若 `radarPng` 在只有 1-2 个会话时抛的不是 `IllegalArgumentException` 而是别的异常，**不要改断言迁就实现**——先确认 `CrossModelProfiler` 对该输入的真实行为，再判断是测试 fixture 不足还是转译范围写错。

- [ ] **Step 5: 风格门 + 提交**

```bash
mvn -o -pl ecbench-app checkstyle:check
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ExportService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/ExportServiceTest.java
git commit -m "feat(web): add export service for PNG charts, ranking and matrix downloads"
```

---

### Task 7: ApiRouter 的 /api/export/* 分支 + DashboardServer 装配

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java:40-57`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java`

**Interfaces:**
- Consumes: Task 6 的 `ExportService` 五个方法与 `Export` record；Task 4 的 `HttpResponses.attachment`。
- Produces: `ApiRouter` 构造器新增第 7 个形参 `ExportService exports`（位于 `JobService jobs` 之后、`IntSupplier portSupplier` 之前）。只有 `DashboardServer` 构造 `ApiRouter`，故无其它调用点需要改。

- [ ] **Step 1: 写失败测试**

在 `DashboardServerTest` 追加。先加一个把余额 CSV 补到三行的辅助方法（放在既有 `send(...)` 方法之后）——**只在本用例内改写，不动 `@BeforeEach` 共享 fixture**，以免影响其它用例对进度/行数的断言：

```java
    /** 把 run_0 的余额 CSV 重写为三行数据，使 XChart 有足够点可绘（只影响调用它的那个用例）。 */
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
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
```

再追加导出端点用例：

```java
    /* -------------------------------------------------------------- 导出端点 */

    @Test
    void exportsBalancePngWithAttachmentHeader() throws Exception {
        widenBalanceCsv();

        HttpResponse<byte[]> response =
                getBytes("/api/export/balance.png?sessions=2026_qwen3.7-plus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(response.headers().firstValue("Content-Disposition"))
                .contains("attachment")
                .contains("balance.png");
        byte[] body = response.body();
        assertThat(body.length).isGreaterThan(0);
        assertThat(body[0] & 0xFF).isEqualTo(0x89);
        assertThat(body[1] & 0xFF).isEqualTo(0x50);
        assertThat(body[2] & 0xFF).isEqualTo(0x4E);
        assertThat(body[3] & 0xFF).isEqualTo(0x47);
    }

    @Test
    void exportsRadarPng() throws Exception {
        HttpResponse<byte[]> response =
                getBytes("/api/export/radar.png?sessions=2026_qwen3.7-plus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition")).contains("radar.png");
        assertThat(response.body()[0] & 0xFF).isEqualTo(0x89);
    }

    @Test
    void exportsRankingCsvAndJsonAndMatrixCsv() throws Exception {
        HttpResponse<String> csv = get("/api/export/ranking.csv?sessions=2026_qwen3.7-plus");
        HttpResponse<String> json = get("/api/export/ranking.json?sessions=2026_qwen3.7-plus");
        HttpResponse<String> matrix = get("/api/export/matrix.csv?sessions=2026_qwen3.7-plus");

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type")).contains("text/csv");
        assertThat(csv.body()).startsWith("rank,model,primary_raw");
        assertThat(json.statusCode()).isEqualTo(200);
        assertThat(json.headers().firstValue("Content-Type")).contains("application/json");
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: 新增 6 个用例失败，报 404（`未知端点: /api/export/...`）；既有用例仍全绿。

- [ ] **Step 3: 实现——ApiRouter 加分支**

3a. 在 `ApiRouter` 的常量区（`JOB_PREFIX` 之后）加：

```java
    private static final String EXPORT_PREFIX = "/api/export/";
```

3b. 加字段与构造器形参。字段区在 `jobs` 之后加 `private final ExportService exports;`；构造器改为：

```java
    public ApiRouter(
            ObjectMapper mapper,
            SessionScanner scanner,
            CompareService compare,
            RunArtifactService artifacts,
            ModelCatalogService models,
            JobService jobs,
            ExportService exports,
            IntSupplier portSupplier) {
        this.mapper = mapper;
        this.scanner = scanner;
        this.compare = compare;
        this.artifacts = artifacts;
        this.models = models;
        this.jobs = jobs;
        this.exports = exports;
        this.portSupplier = portSupplier;
    }
```

3c. 在 `route(...)` 中，把导出分支插在 `if (path.startsWith(JOB_PREFIX))` 块之后、`if (path.startsWith(PREFIX))` 之前：

```java
        if (path.startsWith(EXPORT_PREFIX)) {
            requireGet(method, path);
            export(exchange, path.substring(EXPORT_PREFIX.length()), query(exchange.getRequestURI()));
            return;
        }
```

3d. 新增三个私有方法（放在 `singleJob` 之后）：

```java
    /** 按 kind 分发到 {@link ExportService}，以附件形式写出；未知 kind → 404。 */
    private void export(HttpExchange exchange, String kind, Map<String, List<String>> query)
            throws IOException {
        List<String> sessions = autoSessions(query.get("sessions"));
        String title = first(query.get("title"), null);
        ExportService.Export result =
                switch (kind) {
                    case "balance.png" -> exports.balancePng(sessions, title);
                    case "radar.png" -> exports.radarPng(sessions, title);
                    case "ranking.csv" -> exports.rankingCsv(sessions);
                    case "ranking.json" -> exports.rankingJson(sessions);
                    case "matrix.csv" -> exports.matrixCsv(sessions);
                    default -> throw new ArtifactNotFoundException("未知导出类型: " + kind);
                };
        HttpResponses.attachment(exchange, result.contentType(), result.filename(), result.body());
    }

    /**
     * {@code sessions} 缺省时退化为扫描序前 {@link #MAX_AUTO_SESSIONS} 个会话。
     *
     * <p>与 {@code /api/compare} 共用同一份缺省语义，避免两处规则漂移。
     */
    private List<String> autoSessions(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        List<String> names = new ArrayList<>();
        scanner.scan().stream().limit(MAX_AUTO_SESSIONS).forEach(info -> names.add(info.name()));
        return names;
    }
```

3e. 把既有 `compareNode` 改为复用 `autoSessions`（消除重复，行为不变）：

```java
    private JsonNode compareNode(Map<String, List<String>> query) {
        List<Path> dirs = new ArrayList<>();
        for (String name : autoSessions(query.get("sessions"))) {
            dirs.add(scanner.sessionDir(name));
        }
        return compare.compare(dirs);
    }
```

3f. 检查 import：需要 `io.github.ecommercebench.app.web.ExportService`（同包，**不需要 import**）、`java.util.Map`（已有）。确认没有引入 star import。

- [ ] **Step 4: 实现——DashboardServer 装配**

把 `DashboardServer` 构造器里建 `ApiRouter` 的那段（第 40-57 行）改为：

```java
        ObjectMapper mapper = new ObjectMapper();
        // 先绑定端口再建线程池与路由：绑定失败（如端口被占）时不会泄漏已创建的工作线程，
        // 且 ApiRouter 的 Origin 校验需要读实际绑定端口。
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS);
        this.jobs = jobs;
        SessionScanner scanner = new SessionScanner(logDir);
        CompareService compareService = new CompareService(mapper);
        ApiRouter api =
                new ApiRouter(
                        mapper,
                        scanner,
                        compareService,
                        new RunArtifactService(mapper, roster),
                        models,
                        jobs,
                        new ExportService(mapper, scanner, compareService),
                        () -> httpServer.getAddress().getPort());
        this.httpServer.createContext("/api", api);
        this.httpServer.createContext("/", new StaticAssets());
        this.httpServer.setExecutor(this.executor);
```

`scanner` 与 `compareService` 提为局部变量，是因为 `ExportService` 必须与 `ApiRouter` **共用同一个实例**（否则 `autoSessions` 的扫描结果与导出解析可能来自不同 scanner）。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: PASS，新增 6 个用例全绿，**既有用例零回归**（尤其 `compareDefaultsToAllSessionsWhenUnspecified`、`rejectsNonGetRequest`、`rejectsTraversalAndUnknownEndpoint`——它们锁的正是本次复用的 `autoSessions` 与 `requireGet` 语义）。

- [ ] **Step 6: 全模块 + 风格门**

Run: `mvn -o -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: 0 违规。

- [ ] **Step 7: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java
git commit -m "feat(web): serve PNG and CSV downloads from /api/export endpoints"
```

---

### Task 8: JobFileLoader 抽出 parseLines

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/run/JobFileLoader.java:43-65`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/run/JobFileLoaderTest.java`

**Interfaces:**
- Produces: `public List<RunJob> JobFileLoader.parseLines(List<String> lines)` — Task 10 依赖此签名。**方法名必须是 `parseLines` 而不是 `parse`**：该类已有 `private RunJob parse(JsonNode root)`，同名重载会让两个语义完全不同的 `parse` 共存。

- [ ] **Step 1: 写失败测试**

在 `JobFileLoaderTest` 追加（该文件已有 `JOBS` 常量指向 `src/test/resources/jobs`）：

```java
    @Test
    void parseLinesMatchesLoadForTheSameContent() throws Exception {
        List<String> lines = Files.readAllLines(JOBS.resolve("multiple.jsonl"));

        List<RunJob> fromLines = loader.parseLines(lines);
        List<RunJob> fromFile = loader.load(JOBS.resolve("multiple.jsonl"));

        assertThat(fromLines).hasSize(fromFile.size());
        assertThat(fromLines.get(0).task()).isEqualTo(fromFile.get(0).task());
        assertThat(fromLines.get(0).maxTurns()).isEqualTo(fromFile.get(0).maxTurns());
    }

    @Test
    void parseLinesSkipsBlankLinesAndReportsOneBasedLineNumber() {
        List<String> lines = List.of("", "{\"idx\":0}", "not json at all");

        assertThatThrownBy(() -> loader.parseLines(lines))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 3 行");
    }
```

需要时补 import：`java.nio.file.Files`、`org.assertj.core.api.Assertions.assertThatThrownBy`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=JobFileLoaderTest`
Expected: 编译失败，报 `找不到符号: 方法 parseLines(java.util.List<java.lang.String>)`

- [ ] **Step 3: 实现**

把 `load(Path)`（第 43-65 行）替换为下面两个方法。解析循环**逐字搬移**，不改任何逻辑：

```java
    /** 逐行读取并解析 JSONL，跳过空行；任一行解析失败即抛出含 1 基行号的异常。 */
    public List<RunJob> load(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取 job 文件: " + file, exception);
        }
        return parseLines(lines);
    }

    /**
     * 解析已在内存中的 JSONL 行，规则与 {@link #load(Path)} 完全一致（跳过空行、坏行报 1 基行号）。
     *
     * <p>抽出供 Web 上传路径复用：HTTP 只收文件<b>内容</b>，服务端据此在提交期预校验，使畸形内容能直接
     * 400，而不是等到 run 执行期由 {@code RunComponentFactory} 惰性加载时才 FAILED。
     */
    public List<RunJob> parseLines(List<String> lines) {
        List<RunJob> jobs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                jobs.add(parse(mapper.readTree(line)));
            } catch (IOException | RuntimeException exception) {
                throw new IllegalArgumentException(
                        "job 文件第 " + (i + 1) + " 行解析失败: " + exception.getMessage(), exception);
            }
        }
        return jobs;
    }
```

- [ ] **Step 4: 跑测试确认通过（含既有用例）**

Run: `mvn -o -pl ecbench-app test -Dtest=JobFileLoaderTest`
Expected: PASS。既有 `loadsSingleJobPreservingFields` 等用例必须一字不改地通过。

- [ ] **Step 5: 风格门 + 提交**

```bash
mvn -o -pl ecbench-app checkstyle:check
git add ecbench-app/src/main/java/io/github/ecommercebench/app/run/JobFileLoader.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/run/JobFileLoaderTest.java
git commit -m "refactor(app): extract parseLines so uploaded job content can be pre-validated"
```

---

### Task 9: JobRequest 三参重载 + Job 的临时文件字段

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobRequest.java:46-68`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/Job.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobRequestTest.java`、`JobTest.java`

**Interfaces:**
- Produces:
  - `public static JobRequest JobRequest.parse(JsonNode body, RunOptions server, String jobFileOverride)`；既有两参 `parse(body, server)` 委托它并传 `server.jobFile()`。
  - `public void Job.setTempJobFile(Path path)` 与 `public Path Job.tempJobFile()`（可空，仅供清理）。
- Task 10 依赖以上全部签名。

- [ ] **Step 1: 写失败测试**

在 `JobRequestTest` 追加（沿用该文件已有的 `RunOptions` 测试替身构造方式；若它用的是 `new CliRunOptions()`，则照用）：

```java
    @Test
    void threeArgParseUsesTheJobFileOverride() throws Exception {
        JsonNode body = MAPPER.readTree("{\"model\":\"gpt\"}");

        JobRequest request = JobRequest.parse(body, server, "/tmp/uploaded-123.jsonl");

        assertThat(request.jobFile()).isEqualTo("/tmp/uploaded-123.jsonl");
        // 其余三个路径类字段仍取服务进程配置，不受 override 影响
        assertThat(request.logDir()).isEqualTo(server.logDir());
        assertThat(request.dataDir()).isEqualTo(server.dataDir());
        assertThat(request.tokenizerPath()).isEqualTo(server.tokenizerPath());
    }

    @Test
    void twoArgParseStillTakesJobFileFromServerDefaults() throws Exception {
        JsonNode body = MAPPER.readTree("{\"model\":\"gpt\"}");

        assertThat(JobRequest.parse(body, server).jobFile()).isEqualTo(server.jobFile());
    }
```

`server` 与 `MAPPER` 用该测试类里已有的字段名；若命名不同，以文件里实际的为准。

在 `JobTest` 追加：

```java
    @Test
    void tempJobFileIsNullByDefaultAndRoundTrips() {
        Job job = new Job("id", request);

        assertThat(job.tempJobFile()).isNull();

        job.setTempJobFile(Path.of("/tmp/x.jsonl"));

        assertThat(job.tempJobFile()).isEqualTo(Path.of("/tmp/x.jsonl"));
    }
```

需要时补 import `java.nio.file.Path`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest='JobRequestTest,JobTest'`
Expected: 编译失败，报 `找不到符号: 方法 parse(...,java.lang.String)` 与 `找不到符号: 方法 tempJobFile()`

- [ ] **Step 3: 实现 JobRequest 三参重载**

把 `JobRequest.parse(JsonNode, RunOptions)`（第 46-68 行）改为：

```java
    public static JobRequest parse(JsonNode body, RunOptions server) {
        return parse(body, server, server.jobFile());
    }

    /**
     * 从 POST body 解析请求：校验类型与取值范围，非法输入抛 {@code IllegalArgumentException}。
     *
     * @param body 请求体 JSON
     * @param server 服务进程自身的启动参数，用于取另外 3 个路径类字段
     * @param jobFileOverride 生效的 job 文件路径。Web 上传 {@code jobFileContent} 时由服务端写入临时文件后
     *     传入，<b>覆盖</b>服务进程启动配置里的 {@code --job-file}；未上传时调用方传 {@code server.jobFile()}
     */
    public static JobRequest parse(JsonNode body, RunOptions server, String jobFileOverride) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }
        String model = trimmedText(body, "model");
        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("缺少必填字段 model");
        }
        return new JobRequest(
                model,
                positiveInt(body, "maxTokens", DEFAULT_MAX_TOKENS),
                positiveInt(body, "maxTurns", DEFAULT_MAX_TURNS),
                positiveInt(body, "maxDays", DEFAULT_MAX_DAYS),
                positiveDouble(body, "initialBalance", DEFAULT_INITIAL_BALANCE),
                nonNegativeDouble(body, "dailyFee", DEFAULT_DAILY_FEE),
                positiveInt(body, "maxTokenCapacity", DEFAULT_MAX_TOKEN_CAPACITY),
                positiveInt(body, "runs", DEFAULT_RUNS),
                optionalLong(body, "seed"),
                server.tokenizerPath(),
                server.logDir(),
                jobFileOverride,
                server.dataDir());
    }
```

同时更新类级 Javadoc 中「4 个路径类字段……不接受 body 输入」那句，改为：

```
 * <p>13 个字段中<b>只有 9 个可由 HTTP 提交</b>（model / maxTokens / maxTurns / maxDays / initialBalance /
 * dailyFee / maxTokenCapacity / runs / seed）；3 个路径类字段（tokenizerPath / logDir / dataDir）一律取自
 * 服务进程自身的启动配置，<b>不接受 body 输入</b>——否则本端点就成了任意路径写入入口。{@code jobFile}
 * 同样不接受路径输入，但可经 {@code jobFileContent} 上传<b>内容</b>，由服务端写入自己生成的临时文件。
```

- [ ] **Step 4: 实现 Job 的临时文件字段**

在 `Job` 的 import 段加 `import java.nio.file.Path;`；在 `private volatile Future<?> future;` 之后加字段：

```java
    private volatile Path tempJobFile;
```

在 `future()` 方法之后加两个方法：

```java
    /** 可空的临时 job 文件路径（服务端为上传内容生成），仅用于终态后清理。 */
    public Path tempJobFile() {
        return tempJobFile;
    }

    /** 登记服务端为上传内容生成的临时 job 文件；未上传时不调用，保持 null。 */
    public void setTempJobFile(Path path) {
        this.tempJobFile = path;
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest='JobRequestTest,JobTest'`
Expected: PASS，既有用例零回归。

- [ ] **Step 6: 风格门 + 提交**

```bash
mvn -o -pl ecbench-app checkstyle:check
git add ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobRequest.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/job/Job.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobRequestTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobTest.java
git commit -m "feat(job): accept a server-side job file override and track its temp path"
```

---

### Task 10: JobService 支持 jobFileContent 上传与临时文件生命周期

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobService.java:99-112`（`submit`）、`231-247`（`execute`）、`154-161`（`shutdown`）
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java:209-212`（413 文案）
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobServiceTest.java`、`ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java`

**Interfaces:**
- Consumes: Task 8 的 `JobFileLoader.parseLines(List<String>)`、Task 9 的 `JobRequest.parse(body, server, override)` 与 `Job.setTempJobFile/tempJobFile`。
- Produces: `POST /api/jobs` 接受可选字段 `jobFileContent`（字符串）。

- [ ] **Step 1: 写失败测试（JobServiceTest）**

追加三个用例。`body(...)`、`serviceWith(...)`、`readyGuard()` 均为该文件已有的辅助方法：

```java
    /** 最小合法 job JSONL 单行内容。 */
    private static final String JOB_LINE =
            "{\"task\":\"agent_multiturn/long_horizon/ecommerce_bench\",\"idx\":0,"
                    + "\"data_source\":\"ecommerce_bench\","
                    + "\"agent_info\":{\"max_turn\":10,\"max_day\":2,\"max_tool_response_chars\":4096},"
                    + "\"messages\":[{\"role\":\"system\",\"content\":\"SYS\"}]}";

    @Test
    void uploadedJobContentReachesTheLauncherAsAJobFileAndTempFileIsDeleted() throws Exception {
        List<Path> seenJobFiles = new ArrayList<>();
        JobService svc =
                serviceWith(
                        (options, onSession) -> {
                            seenJobFiles.add(options.runConfig().jobFile());
                            return List.of(new RunOutcome(0, null, null));
                        });

        Job job =
                svc.submit(
                        body(
                                "{\"model\":\"gpt\",\"jobFileContent\":"
                                        + MAPPER.writeValueAsString(JOB_LINE)
                                        + "}"));
        svc.awaitQuiescence(10, TimeUnit.SECONDS);

        assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(seenJobFiles).hasSize(1);
        Path temp = seenJobFiles.get(0);
        assertThat(temp).isNotNull();
        assertThat(temp.getFileName().toString()).startsWith("ecbench-jobs-");
        // 终态后临时文件必须已删
        assertThat(job.tempJobFile()).isEqualTo(temp);
        assertThat(Files.exists(temp)).isFalse();
    }

    @Test
    void malformedJobContentIsRejectedAtSubmitAndTempFileIsNotLeftBehind() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());

        assertThatThrownBy(
                        () ->
                                svc.submit(
                                        body(
                                                "{\"model\":\"gpt\",\"jobFileContent\":"
                                                        + "\"not json at all\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 行");

        assertThat(tempJobFileCount()).isZero();
    }

    @Test
    void blankOrNonTextualJobContentIsRejected() throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of());

        assertThatThrownBy(
                        () -> svc.submit(body("{\"model\":\"gpt\",\"jobFileContent\":\"   \"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(
                        () -> svc.submit(body("{\"model\":\"gpt\",\"jobFileContent\":123}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须是字符串");
    }

    /** 统计系统临时目录里本服务留下的 ecbench-jobs-* 文件数，用于断言清理无遗漏。 */
    private static long tempJobFileCount() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("ecbench-jobs-")).count();
        }
    }
```

需要时补 import：`java.nio.file.Files`、`java.nio.file.Path`、`java.util.ArrayList`、`java.util.stream.Stream`、`org.assertj.core.api.Assertions.assertThatThrownBy`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=JobServiceTest`
Expected: 三个新用例失败（`jobFileContent` 被忽略，`seenJobFiles` 里是 null；空白/非字符串不报错）。

- [ ] **Step 3: 实现 JobService**

3a. 补 import：

```java
import io.github.ecommercebench.app.run.JobFileLoader;
import io.github.ecommercebench.domain.config.RunConfig;
import java.io.UncheckedIOException;
```

3b. 把 `submit(JsonNode body)` 整体替换为：

```java
    public Job submit(JsonNode body) {
        Path tempJobFile = null;
        try {
            tempJobFile = writeUploadedJobFile(body);
            JobRequest request =
                    JobRequest.parse(
                            body,
                            serverDefaults,
                            tempJobFile == null ? serverDefaults.jobFile() : tempJobFile.toString());
            keys.requireReady(request.model());
            synchronized (jobs) {
                long pending = jobs.values().stream().filter(job -> !job.state().terminal()).count();
                if (pending >= MAX_QUEUED) {
                    throw new JobQueueFullException(
                            "任务队列已满（上限 " + MAX_QUEUED + " 个未结束任务）");
                }
                Job job = new Job(UUID.randomUUID().toString(), request);
                job.setTempJobFile(tempJobFile);
                jobs.put(job.id(), job);
                // 所有权移交给 job，由 execute 的 finally 清理；置 null 使下面的 finally 不再重复删
                tempJobFile = null;
                job.attach(executor.submit(() -> execute(job)));
                return job;
            }
        } finally {
            deleteQuietly(tempJobFile);
        }
    }
```

注意 `submit` 的 Javadoc 需补一句：`@throws IllegalArgumentException 参数非法、密钥缺失或上传的 job 内容畸形`。

3c. 在 `submit` 之后新增三个私有/静态方法：

```java
    /**
     * 把上传的 job JSONL 内容写成服务端自己生成的临时文件，并在<b>提交期</b>预校验。
     *
     * <p>路径由服务端生成、不受外部控制，因此不违反「HTTP 不接受路径类参数」的既定收敛——那条收敛防的是
     * 任意路径读写入口。预校验必须在这里做：真正的解析发生在 {@code RunComponentFactory.sharedFileJobs}
     * 的惰性调用里（run 执行期、job 线程上），那时畸形内容只能变成 job FAILED，拿不到 400。
     *
     * <p>预校验用 {@code RunConfig.defaults()} 作 fallback 是安全的：该 fallback 只在 job 行省略上限时
     * 提供缺省值，不影响解析成败。执行期仍会用合并后的 RunConfig 重新解析一次。
     *
     * @return 临时文件路径；body 未带 {@code jobFileContent} 时返回 {@code null}
     * @throws IllegalArgumentException 字段非字符串、内容为空或某行 JSONL 畸形（含 1 基行号）
     */
    private Path writeUploadedJobFile(JsonNode body) {
        JsonNode node = body == null ? null : body.get("jobFileContent");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("jobFileContent 必须是字符串");
        }
        String content = node.asText();
        if (content.isBlank()) {
            throw new IllegalArgumentException("jobFileContent 不能为空");
        }
        // 返回值有意丢弃：这一步只为尽早把畸形内容挡成 400
        new JobFileLoader(mapper, RunConfig.defaults())
                .parseLines(List.of(content.split("\n", -1)));
        Path temp = null;
        try {
            temp = Files.createTempFile("ecbench-jobs-", ".jsonl");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            return temp;
        } catch (IOException exception) {
            deleteQuietly(temp);
            throw new UncheckedIOException("无法写出上传的 job 文件", exception);
        }
    }

    /** 删除临时文件；失败只忽略（系统临时目录会被操作系统回收），绝不让清理异常掩盖真实错误。 */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // 清理失败无妨
        }
    }
```

3d. 把 `execute(Job job)` 替换为（新增 `finally` 与早退路径的清理）：

```java
    /** 在 job 线程上执行一次运行；任何 Throwable 都收敛为终态，绝不让异常逃逸到执行器。 */
    private void execute(Job job) {
        if (!job.markRunning()) {
            // 排队期间已被取消：不会进入 try，需在此单独清理上传的临时文件
            deleteQuietly(job.tempJobFile());
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
        } finally {
            deleteQuietly(job.tempJobFile());
        }
    }
```

3e. 把 `shutdown()` 替换为（末尾兜底清扫）：

```java
    /** 关停执行器：中断在跑的任务并有限等待，不承诺优雅收尾；随后清扫残留的上传临时文件。 */
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        // 兜底：排队中从未执行、或执行线程被强中断而没走到 finally 的 job，其临时文件在此清理。
        // 此时服务正在关停，删掉仍在跑的 job 的临时文件是可接受的（其 RunComponentFactory
        // 若尚未惰性加载 job 文件会随之失败，而该 job 本来也已随执行器一同被中断）。
        for (Job job : list()) {
            deleteQuietly(job.tempJobFile());
        }
    }
```

- [ ] **Step 4: 改 413 文案（ApiRouter）**

把 `readBody` 里抛 413 的那句改为：

```java
                    throw new ApiException(
                            413,
                            "请求体超过上限 "
                                    + MAX_BODY_BYTES
                                    + " 字节；上传 job JSONL 时请注意服务端只使用第一行，"
                                    + "可只提交首个 job 以缩小体积");
```

- [ ] **Step 5: 写失败测试（DashboardServerTest 端到端）**

在 `DashboardServerTest` 追加：

```java
    @Test
    void uploadsJobFileContentAndAcceptsTheJob() throws Exception {
        String jobLine =
                "{\"task\":\"agent_multiturn/long_horizon/ecommerce_bench\",\"idx\":0,"
                        + "\"data_source\":\"ecommerce_bench\","
                        + "\"agent_info\":{\"max_turn\":10,\"max_day\":2},"
                        + "\"messages\":[{\"role\":\"system\",\"content\":\"SYS\"}]}";
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "gpt");
        body.put("maxDays", 2);
        body.put("jobFileContent", jobLine);

        HttpResponse<String> response =
                post("/api/jobs", MAPPER.writeValueAsString(body), null);

        assertThat(response.statusCode()).isEqualTo(202);
        jobService.awaitQuiescence(10, TimeUnit.SECONDS);
    }

    @Test
    void malformedJobFileContentIsBadRequest() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "gpt");
        body.put("jobFileContent", "not json at all");

        HttpResponse<String> response =
                post("/api/jobs", MAPPER.writeValueAsString(body), null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("第 1 行");
    }

    @Test
    void oversizedBodyIsRejectedWithSelfHelpHint() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "gpt");
        body.put("jobFileContent", "x".repeat(70_000));

        HttpResponse<String> response =
                post("/api/jobs", MAPPER.writeValueAsString(body), null);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("65536").contains("只使用第一行");
    }
```

需要时补 import：`com.fasterxml.jackson.databind.node.ObjectNode`、`java.util.concurrent.TimeUnit`（后者该文件可能已有）。

- [ ] **Step 6: 跑全部相关测试**

Run: `mvn -o -pl ecbench-app test -Dtest='JobServiceTest,DashboardServerTest,JobRequestTest,JobTest'`
Expected: PASS。既有的 `submitPropagatesRequestValidationErrors`、`queueBeyondLimitIsRejected`、413 相关用例必须零回归。

- [ ] **Step 7: 全量门禁**

Run: `mvn -o clean test`
Expected: 全模块 BUILD SUCCESS，0 failures。

Run: `mvn -o -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: 0 违规。

- [ ] **Step 8: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobService.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobServiceTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java
git commit -m "feat(job): accept uploaded job JSONL content with temp file lifecycle"
```

---

### Task 11: 前端导出面板与 job 文件上传控件

**Files:**
- Modify: `ecbench-app/src/main/resources/web/index.html`
- Modify: `ecbench-app/src/main/resources/web/app.js`
- Modify: `ecbench-app/src/main/resources/web/app.css`

**Interfaces:**
- Consumes: Task 7 的 5 个 `/api/export/*` 端点、Task 10 的 `jobFileContent` 字段。
- Produces: 5 个 `<a download>`（id 为 `export-balance`/`export-radar`/`export-ranking-csv`/`export-ranking-json`/`export-matrix`）与 1 个 `<input type="file" id="run-jobfile">`。

**约束：** 前端三件套为 **2 空格缩进**；不新增静态资源文件（`StaticAssets` 白名单只有这三个，新增文件会被 404）；导出用纯 `<a href download>`，**不引入 fetch、不用 `createObjectURL`、不新增 JS 状态**。

- [ ] **Step 1: index.html 加导出面板**

在「指标矩阵」那张卡（`<div id="metric-table" …>` 所在的 `<article>`）之后、`</section>` 之前插入：

```html
    <article class="card span-12 reveal">
      <header class="card-head">
        <h2>导出</h2>
        <span class="micro">下载当前勾选会话的产物 · 未勾选时取前 12 个会话</span>
      </header>
      <div id="exports" class="exports">
        <a id="export-balance" href="/api/export/balance.png" download>余额曲线 PNG</a>
        <a id="export-radar" href="/api/export/radar.png" download>七轴雷达 PNG</a>
        <a id="export-ranking-csv" href="/api/export/ranking.csv" download>排名 CSV</a>
        <a id="export-ranking-json" href="/api/export/ranking.json" download>排名 JSON</a>
        <a id="export-matrix" href="/api/export/matrix.csv" download>指标矩阵 CSV</a>
      </div>
      <p class="note">PNG 由服务端用与 <code>evaluate plot</code> / <code>evaluate profile</code> 相同的 XChart 渲染，余额图含该会话的<b>全部 run</b>（不是只有 run_0）。</p>
    </article>
```

- [ ] **Step 2: index.html 加 job 文件控件**

在「新建运行」表单里，`<button id="run-submit" …>` **之前**插入：

```html
      <label class="field"><span>job JSONL（可选）</span>
        <input id="run-jobfile" type="file" accept=".jsonl,application/json,text/plain" />
      </label>
      <p class="note">上限约 60 KB；服务端<b>只使用文件第一行</b>，多 run 文件可只保留首个 job。</p>
```

- [ ] **Step 3: app.js 加导出链接重算**

在 `/* ------------------------------------------------------------ compare */` 分节注释**之前**插入新分节：

```js
/* ------------------------------------------------------------ exports */

/** 导出端点与对应链接元素 id；kind 与后端 /api/export/{kind} 逐字对应。 */
const EXPORT_LINKS = [
  ['export-balance', 'balance.png'],
  ['export-radar', 'radar.png'],
  ['export-ranking-csv', 'ranking.csv'],
  ['export-ranking-json', 'ranking.json'],
  ['export-matrix', 'matrix.csv'],
];

/**
 * 重算 5 个下载链接的 href：带上当前勾选的会话；无勾选时留空 sessions，
 * 由后端按缺省语义取前 12 个会话（与 /api/compare 一致）。
 * 用 <a download> 而非 fetch，因此不涉及跨域、blob 与内存回收。
 */
function refreshExportLinks() {
  const chosen = [...state.selected];
  const query = chosen.length
    ? '?' + chosen.map((n) => 'sessions=' + encodeURIComponent(n)).join('&')
    : '';
  EXPORT_LINKS.forEach(([id, kind]) => {
    $(id).href = '/api/export/' + kind + query;
  });
}
```

- [ ] **Step 4: app.js 挂上重算时机**

4a. 在 `renderSessions()` 的末尾（`});` 闭合 `state.sessions.forEach` 之后、函数结束的 `}` 之前）加一行：

```js
  refreshExportLinks();
```

`renderSessions()` 是所有勾选变化的唯一汇聚点（复选框 `onchange`、全选、清空都会调它），因此挂在这里即可覆盖全部路径。

4b. 在 `loadCompare()` 的「未选择会话」早退分支里，`renderKpis(null);` 之后、`return;` 之前加一行：

```js
    refreshExportLinks();
```

4c. 在 `boot()` 里 `refreshJobs();` 之后加一行，保证首屏（尚未有任何勾选）链接就可用：

```js
  refreshExportLinks();
```

- [ ] **Step 5: app.js 加 job 文件读取与体积预检**

在 `collectRunRequest()` 之后插入：

```js
/** 上传预算：64 KB 是整个请求体的上限，要与其余 9 个字段共享，故留 4 KB 余量。 */
const MAX_JOB_FILE_BYTES = 60 * 1024;

/**
 * 读取用户选中的 job JSONL 文本；未选文件返回 null。
 * 超预算直接 reject、不发请求——服务端会 413，但客户端提前拦能给出更具体的中文提示。
 */
function readJobFile() {
  const input = $('run-jobfile');
  if (!input.files || !input.files.length) return Promise.resolve(null);
  const file = input.files[0];
  if (file.size > MAX_JOB_FILE_BYTES) {
    return Promise.reject(new Error(
      `job 文件 ${file.size} 字节超过上限 ${MAX_JOB_FILE_BYTES} 字节；`
      + '服务端只使用第一行，可只保留首个 job 后重试'));
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('读取 job 文件失败'));
    reader.readAsText(file);
  });
}
```

- [ ] **Step 6: app.js 提交时带上 jobFileContent**

把 `initRunForm()` 里的提交处理中这一行：

```js
      await apiSend('POST', '/api/jobs', collectRunRequest());
```

替换为：

```js
      const payload = collectRunRequest();
      const jobFileContent = await readJobFile();
      if (jobFileContent !== null) payload.jobFileContent = jobFileContent;
      await apiSend('POST', '/api/jobs', payload);
```

`readJobFile()` 的 reject 会被既有的 `catch (error) { showRunError(error.message); }` 接住并显示中文原因，无需新增错误处理分支。

- [ ] **Step 7: app.css 加导出面板样式**

追加（沿用文件里既有的卡片/按钮配色变量，不引入新色值）：

```css
/* ------------------------------------------------------------- exports */

.exports {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.exports a {
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 13px;
  color: var(--ink);
  text-decoration: none;
  white-space: nowrap;
}

.exports a:hover {
  border-color: var(--accent);
  color: var(--accent);
}
```

若 `app.css` 里的 CSS 变量名不是 `--line` / `--ink` / `--accent`，**以文件里实际定义的变量名为准**替换；不要新造变量。
`.exports` 必须带 `min-width: 0`——该项目曾出现过「`.dimension` 缺 `min-width:0` 叠加全局 `white-space:nowrap` 逐属性泄漏导致卡片必然溢出」的缺陷，此处同理。

- [ ] **Step 8: DOM 桩冒烟**

沿用仓库既有做法（参考 `.superpowers/sdd/2026-10-12-dashboard-paper-views/dom-smoke.mjs`），用 node + DOM 桩验证：

- `refreshExportLinks()` 在无勾选时生成不含 `sessions` 的 href；
- 勾选两个会话后 href 含两个 `sessions=` 且已 `encodeURIComponent`；
- `readJobFile()` 在无文件时 resolve `null`；超预算时 reject 且消息含「只使用第一行」。

Run: `node .superpowers/sdd/2026-10-23-web-export/dom-smoke.mjs`
Expected: 全部断言通过。

- [ ] **Step 9: 真实浏览器验收（不可跳过）**

DOM 桩只验渲染逻辑与数据映射，**验不了 CSS 层叠与溢出**。必须真跑一次：

```bash
mvn -o -DskipTests clean package
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080
```

在浏览器里逐项确认：导出面板 5 个按钮不换行溢出、点击能下载、下载到的 PNG 可打开且雷达/余额图有内容、CSV 能被表格软件正确解析成 5 列；job 文件控件选一个 >60 KB 的文件时给出中文提示且**没有发出请求**（看 Network 面板）。

按记忆里的既有教训：验收前确认监听进程的启动时间**晚于**本次构建，否则会拿旧 jar 的表现误判。核对 HTTP 状态码用 `curl -sS -D - -o <body文件> URL` 单条测，不要拼内联 for 循环（会出现重复行与串码）。

- [ ] **Step 10: 提交**

```bash
git add ecbench-app/src/main/resources/web/index.html \
        ecbench-app/src/main/resources/web/app.js \
        ecbench-app/src/main/resources/web/app.css
git commit -m "feat(web): add export downloads and job JSONL upload to dashboard"
```

---

### Task 12: README 更新与全量门禁

**Files:**
- Modify: `README.md`（`### 本地 Web 看板（serve）` 小节的只读接口清单约在第 256-258 行、写侧端点表约在第 282-286 行、「在网页上提交运行」小节约在第 262 行）

- [ ] **Step 1: 补只读接口清单**

在既有的只读接口那句里补上导出端点。原文形如：

```
只读接口：`/api/sessions`、`/api/compare?sessions=<name>`（缺省对比全部会话；返回 `sessions`/`radar`/`ranking`
… `/api/sessions/<name>/{analysis|balance|chatbox}?run=<i>`、`/api/models`、`/api/jobs`。
```

在句末追加：

```
导出接口（全部 GET、只读，返回附件下载）：`/api/export/{balance.png|radar.png|ranking.csv|ranking.json|matrix.csv}?sessions=<name>&sessions=<name>`，
缺省 `sessions` 时取前 12 个会话。余额 PNG 画该会话的**全部 run**（与 `evaluate plot --overlay` 同口径），
图例名带会话前缀以区分同名 run；指标矩阵 CSV 为长表 `session,metric,mean,std,n`。
```

- [ ] **Step 2: 补写侧端点表**

在 `| POST | /api/jobs | 提交运行，返回 202 与任务 id |` 这一行的说明里补上可选字段：

```
| POST | `/api/jobs` | 提交运行，返回 `202` 与任务 id；可选 `jobFileContent` 字段上传预构建 job JSONL 的**内容** |
```

- [ ] **Step 3: 在「在网页上提交运行」小节补上传说明**

追加一段：

```
表单里的「job JSONL（可选）」等价于命令行的 `--job-file`，但上传的是**文件内容**而不是路径——服务端把内容
写进自己生成的临时文件，任务结束后自动删除，因此 HTTP 始终不接受任何路径类参数。两点注意：

- 请求体上限 64 KB（与其它字段共享），前端按 60 KB 预检；
- `RunComponentFactory.resolveJob` **只使用文件第一行**，多 run 的 job 文件可只保留首个 job 再上传。

`logDir` / `dataDir` / `tokenizerPath` 三个路径参数仍只能由服务进程的启动参数决定，Web 无法覆盖。
```

- [ ] **Step 4: 全量门禁**

Run: `mvn -o clean test`
Expected: 全模块 BUILD SUCCESS，0 failures。

Run: `mvn -o -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: 0 违规。

Run: `grep -c "METRIC_DEFS" ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RunComparator.java && git diff --stat HEAD~11 -- ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RunComparator.java`
Expected: `RunComparator.java` 无 diff（冻结契约未被触碰）。

- [ ] **Step 5: CLI 回归（逐条对照改动前输出）**

```bash
mvn -o -DskipTests clean package
java -jar ecbench-app/target/ecbench-app.jar evaluate compare log/20261010_173016_qwen3.7-plus
java -jar ecbench-app/target/ecbench-app.jar evaluate plot \
  --input log/20261010_173016_qwen3.7-plus/balance/run_0_daily_balance.csv \
  --output /tmp/ecbench-balance.png
java -jar ecbench-app/target/ecbench-app.jar evaluate profile log/20261010_173016_qwen3.7-plus \
  --output /tmp/ecbench-radar.png --ranking-output /tmp/ecbench-ranking.csv
```

Expected: 三条命令退出码均为 0，stdout 表格形状与改动前一致，三个产物文件均生成且 PNG 可打开。

- [ ] **Step 6: 导出端点冒烟**

```bash
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080 &
sleep 3
for kind in balance.png radar.png ranking.csv ranking.json matrix.csv; do
  curl -sS -D - -o "/tmp/smoke-$kind" "http://127.0.0.1:8080/api/export/$kind"
done
```

Expected: 5 个响应均 200，`Content-Type` 与 `Content-Disposition: attachment` 齐备，`Content-length` 与落盘文件字节数一致。
（按既有教训：**逐条**用 `-D -` 核对，不要把 curl 拼成内联 for 循环后凭输出判断——那样会出现重复行与串码导致误判。上面的 for 循环逐个串行调用是安全的，但不要在其中再嵌套多条命令。）

收尾清理后台进程用 `kill <PID>`（PID 由 `lsof -nP -iTCP:8080 -sTCP:LISTEN` 取），**不要用 `pkill -f serve`**——该模式串会匹配到执行它的 shell 自身。

- [ ] **Step 7: 提交**

```bash
git add README.md
git commit -m "docs: describe web export endpoints and job JSONL upload"
```

---

## Self-Review 记录

计划写完后按 spec 逐节自查，结果如下。

**1. Spec 覆盖**

| Spec 节 | 实现任务 |
|---|---|
| §4.1 五个导出端点 + 固定 ASCII 文件名 | Task 6、7 |
| §4.2 余额 PNG 的全部 run 枚举 + `isRegularFile` 过滤 + series 标签防重名 | Task 1、5、6 |
| §4.3 雷达 PNG 与 `/api/compare` 同源 | Task 5（`compareAll`）、6 |
| §4.4 长表 CSV 五列、`%.6f`、null 留空、不导出 fallback、不碰 METRIC_DEFS | Task 3 |
| §4.5 `jobFileContent`、64 KB 不变、413 文案、提交期预校验、覆盖优先级 | Task 8、9、10 |
| §5.1 evaluation 三处改动 | Task 1、2、3 |
| §5.2 app.web 六处改动 | Task 4、5、6、7 |
| §5.3 app.run / app.job 四处改动 | Task 8、9、10 |
| §5.4 前端三件套 + 不新增静态文件 | Task 11 |
| §7 错误码映射与三处实现细节 | Task 6（转译范围）、7（405/404）、10（400/413） |
| §8 安全取舍（沿用既有 Origin 校验、不加新层） | 无需改动：`requireSameOrigin` 已覆盖 `POST /api/jobs` |
| §9 测试计划与门禁口径 | 各任务的测试步骤 + Task 12 全量门禁 |
| §10 实施顺序 | 任务编号与之一致 |
| README 更新 | Task 12 |

无遗漏项。

**2. 占位符扫描**：无 TBD/TODO/"类似 Task N"/"补充适当的错误处理"。所有代码步骤都给了完整可粘贴的代码。两处刻意留了「以实际代码为准」的指令（Task 2 Step 2 的 `NormalizedProfile` 构造、Task 11 Step 7 的 CSS 变量名），因为这两个值必须从既有文件读取才准确——指令里都写明了去何处读、以及不得改变的部分（断言的异常类型与文案、不得新造 CSS 变量）。

**3. 类型一致性**：逐一核对跨任务引用的签名——
- `BalancePlotter.png(List<Path>, List<String>, String)`：Task 1 定义 = Task 6 调用 ✓
- `RadarPlotter.png(NormalizedProfile, String)`：Task 2 定义 = Task 6 调用 ✓
- `ComparisonCsvWriter.toLongCsv(ComparisonReport)`：Task 3 定义 = Task 6 调用 ✓
- `HttpResponses.attachment(HttpExchange, String, String, byte[])`：Task 4 定义 = Task 7 调用 ✓
- `SessionScanner.runIndexes(Path) → List<Integer>`：Task 5 定义 = Task 6 调用 ✓
- `CompareService.compareAll(List<Path>) → Compared(report, profile)`：Task 5 定义 = Task 6 调用 ✓
- `ExportService.Export(contentType, filename, body)` 与五个方法：Task 6 定义 = Task 7 调用 ✓
- `JobFileLoader.parseLines(List<String>)`：Task 8 定义 = Task 10 调用 ✓
- `JobRequest.parse(JsonNode, RunOptions, String)`：Task 9 定义 = Task 10 调用 ✓
- `Job.setTempJobFile(Path)` / `tempJobFile()`：Task 9 定义 = Task 10 调用与断言 ✓
- `ApiRouter` 构造器第 7 参 `ExportService`：Task 7 内定义并同任务装配 ✓

**4. 计划编写期发现并回写 spec 的两处偏差**（已在 spec 中修正，此处留档）：
- `JobService` 的关停方法名是 `shutdown()` 而非 spec 初稿写的 `close()`（该类未实现 `AutoCloseable`）。
- `JobFileLoader` 已有 `private RunJob parse(JsonNode)`，故新方法命名 `parseLines` 而非 `parse`，避免两个语义不同的同名重载。

**5. 一处相对 spec 的设计改良**：spec §5.2 原写「`CompareService` 加 `profile(List<Path>)`，`compare()` 改为调用它」。实施时改为 `compareAll(List<Path>) → Compared(report, profile)`，理由是 `ExportService` 的矩阵导出需要 `report`、雷达与排名需要 `profile`，若只暴露 `profile(dirs)` 则矩阵导出要重复跑一次 `RunComparator`；`compareAll` 让两侧都只算一次，且 `compare()` 也不会因委托而重复计算。
