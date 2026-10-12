# 阶段 B：Dashboard 还原论文能力（六维视图 / 浅色 / 全站中文 / 欺诈标注）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让本地 Web 看板按论文 §3.7/§4.3/附录 E–F 的组织方式呈现评估结果（主评分资产倍数、六维度卡片与各维度排名、rank crossings、活动带/五类欺诈/退货层三类诊断图），整站改浅色主题、全站中文（术语保留英文），并在 chatbox 谈判记录上标注供应商欺诈类型。

**Architecture:** 后端只扩一个端点——`/api/compare` 新增 `primaryScore`/`dimensions`/`rankCrossings`/`learning` 四段，由新类 `DimensionService` 自行读 `analysis.json` 计算（与 `CompareService` 并列、互不依赖），学习段取自阶段 A 挂在 `ComparisonReport.SessionComparison.learning()` 上的 `SessionLearning`。chatbox 端点新增供应商标注，来自新的 `SupplierRosterReader`（只读 `data/suppliers.csv` 一张表，容错降级）。前端为零依赖单页应用，改配色变量 + 文案 + 新增渲染函数，图表继续纯 SVG。

**Tech Stack:** Java 17、JDK `com.sun.net.httpserver`、Jackson、Apache Commons CSV（`ecbench-evaluation` 已有直接依赖）、原生 JS + SVG（无框架、无构建、无 CDN）。

**Spec:** `docs/superpowers/specs/2026-10-12-paper-capability-dashboard-design.md`（§3.1 维度口径、§5.1 后端契约、§5.2 浅色调色板、§5.3 中文术语表、§5.4 面板清单、§5.5 矩阵分组）

**前置：** 阶段 A 计划 `docs/superpowers/plans/2026-10-12-learning-metrics-artifact.md` 已全部完成（`SessionLearning`、`LearningMetricsService`、`ProfileAxis.LEARNING = AnchorRatio` 均已就位）。Task 5 的 `learning` 段直接消费其产物。

## Global Constraints

- 语言级别 Java 17；**不新增任何 Maven 依赖**（commons-csv 已是 `ecbench-evaluation` 的直接依赖）。
- 缩进：`ecbench-evaluation` 与 `ecbench-app` 均为 **4 空格**；前端文件沿用既有 2 空格缩进（`app.js`/`app.css`/`index.html` 现状即 2 空格）。
- **禁止运行 `mvn spotless:apply`**（根 pom 的 `<googleJavaFormat/>` 未设 AOSP，会把 4 空格模块整体重排；`spotless:check` 在 evaluation 模块已有 23 个文件的红基线）。
- Checkstyle：行宽 ≤ 120、禁 star import、单方法 ≤ 150 行；**每个新增 Java 类必须有中文 Javadoc**。
- 单测命令统一带 `-am` 与 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 提交：在 `main` 上按任务小步提交（用户已授权）。
- 后端安全不变量不得回归：仅 GET；`{name}` 只允许 `[A-Za-z0-9._-]` 且解析后必须仍在 `--log-dir` 之下（越界 → 400）；非 GET → 405；缺失产物 → 404。新增的 `--catalog` 只影响标注，**不得**成为新的路径穿越入口（它不参与 `{name}` 解析）。
- `/api/compare` 既有的 `sessions`/`radar`/`ranking` 三段**形状与取值不变**（阶段 A 的回归测试已锁定），本阶段只追加新段。
- 前端中文文案与术语译名**必须**照抄 spec §5.3 的对照表，不得自创译名；论文/产物原始字段名（`CSE+`、`BadSpend%`、`final_balance`、`calls_by_band` 等）保留英文原文。
- 浅色主题只换配色：版式、栅格类名（`span-5`/`span-7`/`span-12`）、字体族、reveal 动效、SVG 绘制逻辑一律不动。

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `ecbench-evaluation/.../evaluation/io/SupplierRosterReader.java` | 只读 `suppliers.csv`，产出 email → 供应商属性 | 新 |
| `ecbench-app/.../app/web/DimensionAxis.java` | 六维度的口径定义（排名指标 + 诊断指标 JSON path） | 新 |
| `ecbench-app/.../app/web/DimensionService.java` | 读 analysis.json，算维度值/排名/交叉/诊断块 | 新 |
| `ecbench-app/.../app/web/CompareService.java` | `/api/compare` 序列化 | 改：追加四段 |
| `ecbench-app/.../app/web/RunArtifactService.java` | 单 run 下钻产物 | 改：chatbox 加标注 |
| `ecbench-app/.../app/web/DashboardServer.java` | HTTP 生命周期与装配 | 改：+roster 参数 |
| `ecbench-app/.../app/cli/ServeCommand.java` | `serve` 子命令 | 改：+`--catalog` |
| `ecbench-app/src/main/resources/web/app.css` | 样式 | 改：浅色调色板 + 12 处硬编码色 |
| `ecbench-app/src/main/resources/web/index.html` | 页面骨架 | 改：中文文案 + 新面板容器 |
| `ecbench-app/src/main/resources/web/app.js` | 前端逻辑 | 改：PALETTE/描边色 + 中文 + 6 个新渲染函数 |
| `README.md` | 使用说明 | 改：serve 小节 |

测试：`SupplierRosterReaderTest`（新）、`DimensionServiceTest`（新）、`CompareServiceTest`（改）、`RunArtifactServiceTest`（改）、`DashboardServerTest`（改）。前端不写 JS 单测（沿用既有裁定），以 Task 9 的浏览器验收为准。

---

### Task 1: 供应商名册读取器（chatbox 欺诈标注的数据源）

**Files:**
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/SupplierRosterReader.java`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/SupplierRosterReaderTest.java`

**Interfaces:**
- Produces:
  - `SupplierRosterReader.RosterEntry(String email, String name, String type, String personality, String fraudType)` —— 嵌套 record；`fraudType` 为空/空白时归一为 null
  - `SupplierRosterReader()` / `SupplierRosterReader(ObjectMapper)`（`ObjectMapper` 仅为与同包读取器保持一致的注入点，本类实际用 commons-csv 解析）
  - `readByEmail(Path suppliersCsv) → Map<String, RosterEntry>`，键为**小写化**的 `supplier_email`；文件缺失 / 表头缺 `supplier_email` 列 / 单行坏值 → 跳过该行并在 stderr 记一次警告，返回已解析部分（**永不抛异常**，保证只读看板不因名册问题失败）

- [ ] **Step 1: 写失败测试**

新建 `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/SupplierRosterReaderTest.java`：

```java
package io.github.ecommercebench.evaluation.io;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 供应商名册读取器测试：按 email 建索引、空 fraud_type 归一为 null、缺文件/缺列降级为空 map 而不抛。 */
class SupplierRosterReaderTest {

    private static final String HEADER =
            "supplier_id,supplier_name,supplier_email,supplier_type,personality,urgency,"
                    + "fraud_type,categories_served,bankruptcy_threshold\n";

    private final SupplierRosterReader reader = new SupplierRosterReader();

    @Test
    void indexesByEmailAndKeepsTypePersonalityFraudType(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(
                csv,
                HEADER
                        + "SUP-0001,Swift Supply,SwiftSupply.sup-0001@wholesale.com,good,Enthusiastic,0.816,,"
                        + "Major Appliances,11\n"
                        + "SUP-0241,Cedar Distribution,cedardistribution.sup-0241@wholesale.com,bad,Tough,0.5,"
                        + "vip_fee,All,7\n");

        Map<String, RosterEntry> roster = reader.readByEmail(csv);

        assertThat(roster).hasSize(2);
        RosterEntry good = roster.get("swiftsupply.sup-0001@wholesale.com");
        assertThat(good.name()).isEqualTo("Swift Supply");
        assertThat(good.type()).isEqualTo("good");
        assertThat(good.personality()).isEqualTo("Enthusiastic");
        assertThat(good.fraudType()).isNull();

        RosterEntry bad = roster.get("cedardistribution.sup-0241@wholesale.com");
        assertThat(bad.type()).isEqualTo("bad");
        assertThat(bad.fraudType()).isEqualTo("vip_fee");
    }

    @Test
    void skipsRowsWithoutEmailAndKeepsTheRest(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(
                csv,
                HEADER
                        + "SUP-0002,No Email,,good,Friendly,0.4,,All,5\n"
                        + "SUP-0003,Oak Link,oaklink.sup-0003@wholesale.com,good,Friendly,0.4,,All,5\n");

        Map<String, RosterEntry> roster = reader.readByEmail(csv);

        assertThat(roster).hasSize(1);
        assertThat(roster).containsKey("oaklink.sup-0003@wholesale.com");
    }

    @Test
    void missingFileYieldsEmptyMap(@TempDir Path dir) {
        assertThat(reader.readByEmail(dir.resolve("absent.csv"))).isEmpty();
    }

    @Test
    void missingEmailColumnYieldsEmptyMap(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("suppliers.csv");
        Files.writeString(csv, "supplier_id,supplier_name,supplier_type\nSUP-1,Foo,good\n");

        assertThat(reader.readByEmail(csv)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=SupplierRosterReaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 SupplierRosterReader`

- [ ] **Step 3: 实现读取器**

新建 `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/SupplierRosterReader.java`：

```java
package io.github.ecommercebench.evaluation.io;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * 供应商名册读取器：只解析 {@code data/suppliers.csv} 一张表，按 email 建索引。
 *
 * <p>供 dashboard 在 chatbox 谈判记录上标注供应商类型（诚信/欺诈）、人格与欺诈类型（论文附录 G 的失败案例阅读方式）。
 * 刻意**不**复用 {@code CsvCatalogLoader.load()}——后者要求六张 CSV 齐全且任一缺失即抛异常，不适合只读看板。
 * 本读取器全程容错：文件缺失、表头缺 {@code supplier_email} 列、单行坏值都只跳过并在 stderr 记一次警告，永不抛异常。
 */
public final class SupplierRosterReader {

    private static final CSVFormat FORMAT =
            CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();

    /**
     * 名册条目。
     *
     * @param email 小写化的供应商邮箱（索引键）
     * @param fraudType 欺诈类型；诚信供应商为 null
     */
    public record RosterEntry(
            String email, String name, String type, String personality, String fraudType) {}

    /** 按 email（小写）索引名册；任何不可用情形都返回已解析部分，可能为空 map。 */
    public Map<String, RosterEntry> readByEmail(Path suppliersCsv) {
        Map<String, RosterEntry> roster = new LinkedHashMap<>();
        if (suppliersCsv == null || !Files.isRegularFile(suppliersCsv)) {
            warn("供应商名册不存在，跳过欺诈标注: " + suppliersCsv);
            return roster;
        }
        try (Reader source = Files.newBufferedReader(suppliersCsv, StandardCharsets.UTF_8);
                CSVParser parser = FORMAT.parse(source)) {
            if (!parser.getHeaderMap().containsKey("supplier_email")) {
                warn("供应商名册缺少 supplier_email 列，跳过欺诈标注: " + suppliersCsv);
                return roster;
            }
            for (CSVRecord record : parser) {
                RosterEntry entry = toEntry(record);
                if (entry != null) {
                    roster.put(entry.email(), entry);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            warn("供应商名册解析中断（已保留 " + roster.size() + " 条）: " + exception.getMessage());
        }
        return roster;
    }

    private static RosterEntry toEntry(CSVRecord record) {
        String email = trimmed(record, "supplier_email");
        if (email.isEmpty()) {
            return null;
        }
        String fraudType = trimmed(record, "fraud_type");
        return new RosterEntry(
                email.toLowerCase(Locale.ROOT),
                trimmed(record, "supplier_name"),
                trimmed(record, "supplier_type"),
                trimmed(record, "personality"),
                fraudType.isEmpty() ? null : fraudType);
    }

    /** 取列值；列不存在时返回空串而非抛异常（commons-csv 对缺失列会抛 IllegalArgumentException）。 */
    private static String trimmed(CSVRecord record, String column) {
        return record.isMapped(column) ? record.get(column).trim() : "";
    }

    private static void warn(String message) {
        System.err.println("[serve] " + message);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=SupplierRosterReaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 tests）

- [ ] **Step 5: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/SupplierRosterReader.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/SupplierRosterReaderTest.java
git commit -m "feat(evaluation): read supplier roster for chatbox fraud annotation"
```

---

### Task 2: 六维度口径定义（DimensionAxis）

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DimensionAxis.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DimensionAxisTest.java`

**Interfaces:**
- Consumes: `com.fasterxml.jackson.databind.JsonNode`（单个 run 的 `analysis.json` 树）
- Produces:
  - `enum DimensionAxis { NEGOTIATION, FRAUD, SOLVENCY, EFFICIENCY, EXECUTION, LEARNING }`（**顺序即论文 §4.3.1–4.3.6 顺序，不得调整**）
  - `key() → String`、`labelZh() → String`、`paperRef() → String`、`rankLabel() → String`、`rankLabelZh() → String`、`unit() → String`、`lowerIsBetter() → boolean`
  - `raw(JsonNode analysis) → Double`：该维度的**排名指标**在单个 run 上的原始值；缺字段或分母为 0 → null。`LEARNING.raw()` 恒为 null（学习维度的值来自阶段 A 的 `SessionLearning`，不在 analysis.json 里）
  - `Diagnostic(String label, String labelZh, String... path)` 嵌套 record + `diagnostics() → List<Diagnostic>`：该维度的诊断指标（JSON path 取值）；`LEARNING.diagnostics()` 返回空列表
  - `static Double at(JsonNode root, String... path)`：按 path 取数值，缺失/非数值 → null（`DimensionService` 也复用它）

- [ ] **Step 1: 写失败测试**

新建 `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DimensionAxisTest.java`：

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 六维度口径测试：轴序即论文 §4.3 顺序、排名指标方向、派生比值的分母保护、诊断指标 path。 */
class DimensionAxisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode analysis(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void axisOrderFollowsPaperSectionOrder() {
        assertThat(DimensionAxis.values())
                .containsExactly(
                        DimensionAxis.NEGOTIATION,
                        DimensionAxis.FRAUD,
                        DimensionAxis.SOLVENCY,
                        DimensionAxis.EFFICIENCY,
                        DimensionAxis.EXECUTION,
                        DimensionAxis.LEARNING);
        assertThat(Arrays.stream(DimensionAxis.values()).map(DimensionAxis::key).toList())
                .containsExactly(
                        "negotiation", "fraud", "solvency", "efficiency", "execution", "learning");
    }

    @Test
    void directionsMatchPaper() {
        assertThat(DimensionAxis.NEGOTIATION.lowerIsBetter()).isFalse();
        assertThat(DimensionAxis.EFFICIENCY.lowerIsBetter()).isFalse();
        assertThat(DimensionAxis.FRAUD.lowerIsBetter()).isTrue();
        assertThat(DimensionAxis.SOLVENCY.lowerIsBetter()).isTrue();
        assertThat(DimensionAxis.EXECUTION.lowerIsBetter()).isTrue();
        assertThat(DimensionAxis.LEARNING.lowerIsBetter()).isTrue();
    }

    @Test
    void negotiationRankMetricIsCsePlus() throws Exception {
        JsonNode node = analysis("{\"negotiation_quality\":{\"CSE+\":0.5005}}");

        assertThat(DimensionAxis.NEGOTIATION.raw(node)).isCloseTo(0.5005, within(1e-9));
        assertThat(DimensionAxis.NEGOTIATION.rankLabel()).isEqualTo("CSE+");
    }

    @Test
    void solvencyRankMetricIsDrawdownOverPeakAssets() throws Exception {
        JsonNode node =
                analysis(
                        "{\"profitability\":{\"peak_drawdown\":20000.0,\"peak_total_assets\":200000.0}}");

        assertThat(DimensionAxis.SOLVENCY.raw(node)).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void zeroDenominatorYieldsNullForDerivedRankMetrics() throws Exception {
        JsonNode zeroAssets =
                analysis("{\"profitability\":{\"peak_drawdown\":1000.0,\"peak_total_assets\":0.0}}");
        JsonNode zeroCalls =
                analysis(
                        "{\"profitability\":{\"final_balance\":150000.0,\"initial_balance\":100000.0},"
                                + "\"operational_efficiency\":{\"total_tool_calls\":0}}");

        assertThat(DimensionAxis.SOLVENCY.raw(zeroAssets)).isNull();
        assertThat(DimensionAxis.EFFICIENCY.raw(zeroCalls)).isNull();
    }

    @Test
    void efficiencyRankMetricIsProfitPerToolCall() throws Exception {
        JsonNode node =
                analysis(
                        "{\"profitability\":{\"final_balance\":150000.0,\"initial_balance\":100000.0},"
                                + "\"operational_efficiency\":{\"total_tool_calls\":500}}");

        assertThat(DimensionAxis.EFFICIENCY.raw(node)).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void learningAxisHasNoAnalysisValueAndNoDiagnostics() throws Exception {
        JsonNode node = analysis("{\"negotiation_quality\":{\"learning_speed\":{\"se_half_lift\":0.05}}}");

        assertThat(DimensionAxis.LEARNING.raw(node)).isNull();
        assertThat(DimensionAxis.LEARNING.diagnostics()).isEmpty();
        assertThat(DimensionAxis.LEARNING.rankLabel()).isEqualTo("AnchorRatio");
    }

    @Test
    void diagnosticsExposePaperLabelsWithPaths() {
        List<DimensionAxis.Diagnostic> fraud = DimensionAxis.FRAUD.diagnostics();

        assertThat(fraud).isNotEmpty();
        assertThat(fraud).anySatisfy(d -> {
            assertThat(d.label()).isEqualTo("spend_on_bad");
            assertThat(d.labelZh()).isEqualTo("欺诈供应商支出");
            assertThat(d.path()).containsExactly("fraud_identification", "spend_on_bad_supplier");
        });
        assertThat(DimensionAxis.NEGOTIATION.diagnostics())
                .extracting(DimensionAxis.Diagnostic::label)
                .contains("SE+", "%Oracle", "AGR+", "avg_rounds", "money_saved");
    }

    @Test
    void missingFieldYieldsNullNotZero() throws Exception {
        assertThat(DimensionAxis.at(analysis("{}"), "profitability", "final_balance")).isNull();
        assertThat(DimensionAxis.at(analysis("{\"profitability\":{}}"), "profitability", "final_balance"))
                .isNull();
        assertThat(DimensionAxis.at(analysis("{\"profitability\":{\"bankrupt\":true}}"),
                "profitability", "bankrupt")).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-app -am test -Dtest=DimensionAxisTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 DimensionAxis`

- [ ] **Step 3: 实现枚举**

新建 `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DimensionAxis.java`：

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 论文 §3.7 / §4.3 的六个评估维度及其排名指标口径（dashboard 维度视图的唯一定义处）。
 *
 * <p>枚举顺序即论文 §4.3.1–4.3.6 的顺序，前端按此顺序渲染维度卡。每维度给出：排名指标（{@link #raw} 从单个 run 的
 * {@code analysis.json} 取原始值，派生比值在分母为 0 时返回 null）、方向（{@link #lowerIsBetter}）、以及一组按 JSON path
 * 取值的诊断指标（{@link #diagnostics}）。学习维度例外：它的排名指标 AnchorRatio 来自论文 §E.6 的重算结果（逐谈判记录），
 * 不在 {@code analysis.json} 内，故 {@link #raw} 恒为 null、{@link #diagnostics} 为空，其数值由 {@code DimensionService}
 * 从 {@code SessionLearning} 填充。
 */
public enum DimensionAxis {
    NEGOTIATION("negotiation", "谈判质量", "§4.3.1 / §E.1", "CSE+", "已成交剩余效率", "", false),
    FRAUD("fraud", "欺诈规避", "§4.3.2 / §E.2", "BadSpend%", "欺诈支出占比", "", true),
    SOLVENCY("solvency", "现金流与偿付", "§4.3.3 / §E.3", "drawdown / peak assets", "回撤比", "", true),
    EFFICIENCY("efficiency", "运营效率", "§4.3.4 / §E.4", "profit / tool call", "每次工具调用利润", "¥", false),
    EXECUTION("execution", "运营执行", "§4.3.5 / §E.5", "controllable return rate", "可控退货率", "", true),
    LEARNING("learning", "长周期学习", "§4.3.6 / §E.6", "AnchorRatio", "锚定比", "", true);

    /**
     * 一个诊断指标：论文/产物原始名 + 中文注解 + 在 analysis.json 中的取值路径。
     */
    public record Diagnostic(String label, String labelZh, List<String> path) {

        Diagnostic(String label, String labelZh, String... path) {
            this(label, labelZh, List.of(path));
        }
    }

    private final String key;
    private final String labelZh;
    private final String paperRef;
    private final String rankLabel;
    private final String rankLabelZh;
    private final String unit;
    private final boolean lowerIsBetter;

    DimensionAxis(
            String key,
            String labelZh,
            String paperRef,
            String rankLabel,
            String rankLabelZh,
            String unit,
            boolean lowerIsBetter) {
        this.key = key;
        this.labelZh = labelZh;
        this.paperRef = paperRef;
        this.rankLabel = rankLabel;
        this.rankLabelZh = rankLabelZh;
        this.unit = unit;
        this.lowerIsBetter = lowerIsBetter;
    }

    public String key() {
        return key;
    }

    public String labelZh() {
        return labelZh;
    }

    public String paperRef() {
        return paperRef;
    }

    public String rankLabel() {
        return rankLabel;
    }

    public String rankLabelZh() {
        return rankLabelZh;
    }

    public String unit() {
        return unit;
    }

    /** 排名指标是否为「越低越好」（决定 rank 1 的方向）。 */
    public boolean lowerIsBetter() {
        return lowerIsBetter;
    }

    /** 该维度排名指标在单个 run 上的原始值；缺字段或分母为 0 时返回 null。学习维度恒为 null。 */
    public Double raw(JsonNode analysis) {
        return switch (this) {
            case NEGOTIATION -> at(analysis, "negotiation_quality", "CSE+");
            case FRAUD -> at(analysis, "fraud_identification", "spend_on_bad_supplier_share");
            case SOLVENCY -> ratio(
                    at(analysis, "profitability", "peak_drawdown"),
                    at(analysis, "profitability", "peak_total_assets"));
            case EFFICIENCY -> ratio(
                    difference(
                            at(analysis, "profitability", "final_balance"),
                            at(analysis, "profitability", "initial_balance")),
                    at(analysis, "operational_efficiency", "total_tool_calls"));
            case EXECUTION -> at(analysis, "return_management", "controllable_return_rate");
            case LEARNING -> null;
        };
    }

    /** 该维度的诊断指标清单（学习维度为空，其数值由 SessionLearning 提供）。 */
    public List<Diagnostic> diagnostics() {
        return switch (this) {
            case NEGOTIATION -> List.of(
                    new Diagnostic("SE+", "剩余效率", "negotiation_quality", "SE+"),
                    new Diagnostic("%Oracle", "预言机归一化剩余", "negotiation_quality", "%Oracle"),
                    new Diagnostic("AGR+", "诚信供应商成交率", "negotiation_quality", "AGR+"),
                    new Diagnostic("avg_rounds", "达成交易所需轮次",
                            "negotiation_quality", "avg_rounds_to_deal"),
                    new Diagnostic("money_saved", "相对初始报价的节省",
                            "negotiation_quality", "total_money_saved_vs_initial"));
            case FRAUD -> List.of(
                    new Diagnostic("bad_order_share", "欺诈供应商订单占比",
                            "fraud_identification", "bad_supplier_order_share"),
                    new Diagnostic("spend_on_bad", "欺诈供应商支出",
                            "fraud_identification", "spend_on_bad_supplier"),
                    new Diagnostic("vip_fee_paid_count", "已支付会员费次数",
                            "fraud_identification", "vip_fee_paid_count"),
                    new Diagnostic("vip_fee_paid_amount", "已支付会员费金额",
                            "fraud_identification", "vip_fee_paid_amount"),
                    new Diagnostic("contacted_bad", "接触过的欺诈供应商数",
                            "supplier_engagement", "contacted", "distinct_bad"),
                    new Diagnostic("ordered_bad", "下过单的欺诈供应商数",
                            "supplier_engagement", "ordered", "distinct_bad"));
            case SOLVENCY -> List.of(
                    new Diagnostic("peak_drawdown", "峰值回撤", "profitability", "peak_drawdown"),
                    new Diagnostic("peak_total_assets", "峰值总资产",
                            "profitability", "peak_total_assets"),
                    new Diagnostic("bankrupt", "是否破产", "profitability", "bankrupt"),
                    new Diagnostic("final_day", "结束日", "profitability", "final_day"));
            case EFFICIENCY -> List.of(
                    new Diagnostic("profit_per_tool_call", "每次工具调用利润",
                            "operational_efficiency", "profit_per_tool_call"),
                    new Diagnostic("tool_calls", "工具调用数",
                            "operational_efficiency", "total_tool_calls"),
                    new Diagnostic("turns", "交互轮次", "operational_efficiency", "turns"),
                    new Diagnostic("context_evictions", "上下文驱逐次数",
                            "operational_efficiency", "context_evictions"),
                    new Diagnostic("memory_calls", "记忆访问次数",
                            "operational_efficiency", "memory_calls"));
            case EXECUTION -> List.of(
                    new Diagnostic("controllable_return_rate", "可控退货率",
                            "return_management", "controllable_return_rate"),
                    new Diagnostic("realized_return_rate", "实际退货率",
                            "return_management", "realized_return_rate"),
                    new Diagnostic("on_time_ship", "准时发货率",
                            "fulfilment_quality", "on_time_ship_rate"),
                    new Diagnostic("refund_loss_total", "退款损失",
                            "return_management", "refund_loss_total"),
                    new Diagnostic("shipping_loss_on_returns", "退货运费损失",
                            "return_management", "shipping_loss_on_returns"));
            case LEARNING -> List.of();
        };
    }

    /** 按 path 取数值：缺失、null 或非数值 → null；布尔按 1.0/0.0 计（与 RunComparator 一致）。 */
    public static Double at(JsonNode root, String... path) {
        JsonNode current = root;
        for (String key : path) {
            if (current == null) {
                return null;
            }
            current = current.get(key);
        }
        if (current == null || current.isNull()) {
            return null;
        }
        if (current.isNumber()) {
            return current.asDouble();
        }
        return current.isBoolean() ? (current.booleanValue() ? 1.0 : 0.0) : null;
    }

    private static Double difference(Double minuend, Double subtrahend) {
        return minuend == null || subtrahend == null ? null : minuend - subtrahend;
    }

    private static Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0.0) {
            return null;
        }
        return numerator / denominator;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ecbench-app -am test -Dtest=DimensionAxisTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（9 tests）

- [ ] **Step 5: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/DimensionAxis.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DimensionAxisTest.java
git commit -m "feat(web): define paper §4.3 dimension metric contracts"
```

---

### Task 3: 维度服务（值 / 排名 / 交叉 / 诊断块）

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DimensionService.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DimensionServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 `DimensionAxis`（含 `at`/`raw`/`diagnostics`）、`evaluation.io.AnalysisJsonReader`、`evaluation.io.RunLayoutResolver`、阶段 A 的 `ComparisonReport`（读 `SessionComparison.learning()`）
- Produces:
  - `DimensionService(ObjectMapper mapper)`
  - `ObjectNode node(List<Path> sessionDirs, ComparisonReport report) → ObjectNode`，含四段：`primaryScore`、`dimensions`、`rankCrossings`、`learning`（JSON 形状见 spec §5.1，逐字段一致）
  - 契约：`sessionDirs` 与 `report.sessions()` **同序同长**（`RunComparator.compare` 按输入顺序逐目录产出一个 `SessionComparison`）；空输入返回四段齐备的空结构（`dimensions` 仍含 6 个维度对象，其 `rows` 为空数组）
  - 会话级取值：各 run 的均值，null run 跳过不计入分母；全 null → value=null 且 rank=null
  - 排名：rank 1 = 该维度最好（按 `lowerIsBetter` 定向）；并列取 competition ranking（1,2,2,4）
  - 三个嵌套诊断块：`spendByFraudType`（5 个欺诈型的 orders/units/spend 均值）、`callsByBand`（8 类活动带的计数均值 + 占比均值）、`returnLayers`（四层退货率均值）、`shipSpeed`（fast/standard/slow 计数均值），挂在对应维度对象的 `blocks` 字段下

- [ ] **Step 1: 写失败测试**

新建 `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DimensionServiceTest.java`：

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.evaluation.RunComparator;
import io.github.ecommercebench.evaluation.model.ComparisonReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 维度服务测试：主评分、六维度值与排名、rank crossings、嵌套诊断块、空输入与缺字段的降级。 */
class DimensionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个字段齐备的 analysis.json；cse/badShare/toolCalls 可调以制造排名差异。 */
    private static String analysis(
            double finalScore, double cse, double badShare, double drawdown, double peakAssets,
            double finalBalance, int toolCalls, double controllable) {
        return "{"
                + "\"reward\":{\"final_score\":" + finalScore + ",\"termination_reason\":\"env_terminated\","
                + "\"termination_detail\":\"max_turns_reached\"},"
                + "\"profitability\":{\"final_balance\":" + finalBalance + ",\"initial_balance\":100000.0,"
                + "\"peak_drawdown\":" + drawdown + ",\"peak_total_assets\":" + peakAssets + ","
                + "\"bankrupt\":false,\"final_day\":365,\"stores_opened\":3,\"store_reopens\":0},"
                + "\"negotiation_quality\":{\"SE+\":" + cse + ",\"CSE+\":" + cse + ",\"%Oracle\":" + cse + ","
                + "\"AGR+\":1.0,\"avg_rounds_to_deal\":3.5,\"total_money_saved_vs_initial\":0.1,"
                + "\"learning_speed\":{}},"
                + "\"fraud_identification\":{\"bad_supplier_order_share\":0.0,"
                + "\"spend_on_bad_supplier\":" + (badShare * 1000) + ",\"spend_on_bad_supplier_share\":" + badShare + ","
                + "\"vip_fee_paid_count\":0,\"vip_fee_paid_amount\":0.0,"
                + "\"spend_by_fraud_type\":{\"vip_fee\":{\"orders\":1,\"units\":2,\"spend\":300.0},"
                + "\"qty_bait\":{\"orders\":0,\"units\":0,\"spend\":0.0}},"
                + "\"spend_by_good_personality\":{\"Friendly\":591.9}},"
                + "\"supplier_engagement\":{\"contacted\":{\"distinct_total\":5,\"distinct_good\":4,"
                + "\"distinct_bad\":1},\"ordered\":{\"distinct_total\":2,\"distinct_good\":2,\"distinct_bad\":0},"
                + "\"roster_totals\":{\"distinct_total\":576,\"distinct_good\":424,\"distinct_bad\":152}},"
                + "\"fulfilment_quality\":{\"orders_sold\":10,\"orders_shipped\":9,\"orders_cancelled\":1,"
                + "\"on_time_ship_rate\":0.9,\"units_sold\":20,\"units_returned\":2,"
                + "\"realized_return_rate\":0.1,\"ship_speed_counts\":{\"fast\":3,\"standard\":5,\"slow\":1}},"
                + "\"return_management\":{\"units_sold\":20,\"units_returned\":2,\"realized_return_rate\":0.1,"
                + "\"refund_loss_total\":120.0,\"shipping_loss_on_returns\":30.0,\"units_shipped\":20,"
                + "\"exp_return_rate\":0.15,\"exp_return_rate_natural\":0.05,"
                + "\"exp_return_rate_from_pricing\":0.06,\"exp_return_rate_from_ship_speed\":0.03,"
                + "\"exp_return_rate_from_defective_fraud\":0.01,\"controllable_return_rate\":" + controllable + ","
                + "\"ship_speed_counts\":{\"fast\":3,\"standard\":5,\"slow\":1}},"
                + "\"operational_efficiency\":{\"total_tool_calls\":" + toolCalls + ",\"turns\":" + toolCalls + ","
                + "\"profit_per_tool_call\":10.0,\"calls_by_band\":{\"waiting\":2,\"shipping\":1,\"listing\":0,"
                + "\"withdraw\":0,\"bargaining\":6,\"state_polling\":5,\"memory\":0,\"remainder\":16},"
                + "\"calls_by_band_share\":{\"waiting\":6.67,\"shipping\":3.33,\"listing\":0.0,\"withdraw\":0.0,"
                + "\"bargaining\":20.0,\"state_polling\":16.67,\"memory\":0.0,\"remainder\":53.33},"
                + "\"memory_calls\":0,\"memory_share\":0.0,\"context_evictions\":0,\"context_tokens_freed\":0}"
                + "}";
    }

    private static Path session(Path root, String name, String json) throws Exception {
        Path metrics = root.resolve(name).resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(metrics.resolve("run_0_analysis.json"), json);
        return root.resolve(name);
    }

    private static ObjectNode compare(List<Path> dirs) {
        ComparisonReport report = new RunComparator().compare(dirs);
        return new DimensionService(MAPPER).node(dirs, report);
    }

    @Test
    void primaryScoreIsTheAssetMultiplierWithRanks(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));
        Path b = session(root, "2026_b", analysis(0.9, 0.4, 0.3, 30000, 180000, 90000, 400, 0.20));

        JsonNode primary = compare(List.of(a, b)).path("primaryScore");

        assertThat(primary.path("labelZh").asText()).isEqualTo("年末总资产倍数");
        assertThat(primary.path("unit").asText()).isEqualTo("×");
        assertThat(primary.path("rows")).hasSize(2);
        assertThat(primary.path("rows").get(0).path("name").asText()).isEqualTo("2026_a");
        assertThat(primary.path("rows").get(0).path("value").asDouble()).isCloseTo(1.5, within(1e-9));
        assertThat(primary.path("rows").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(primary.path("rows").get(1).path("rank").asInt()).isEqualTo(2);
        // spec §5.4：主评分卡还要带年末总资产均值、破产 run 数与终止原因
        assertThat(primary.path("rows").get(0).path("finalBalance").asDouble())
                .isCloseTo(150000.0, within(1e-9));
        assertThat(primary.path("rows").get(0).path("bankruptRuns").asInt()).isZero();
        assertThat(primary.path("rows").get(0).path("termination").asText())
                .isEqualTo("env_terminated");
    }

    @Test
    void sixDimensionsAreEmittedInPaperOrder(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode dimensions = compare(List.of(a)).path("dimensions");

        assertThat(dimensions).hasSize(6);
        assertThat(dimensions.get(0).path("key").asText()).isEqualTo("negotiation");
        assertThat(dimensions.get(0).path("labelZh").asText()).isEqualTo("谈判质量");
        assertThat(dimensions.get(0).path("paperRef").asText()).isEqualTo("§4.3.1 / §E.1");
        assertThat(dimensions.get(0).path("rankMetric").path("label").asText()).isEqualTo("CSE+");
        assertThat(dimensions.get(0).path("rankMetric").path("direction").asText()).isEqualTo("higher");
        assertThat(dimensions.get(5).path("key").asText()).isEqualTo("learning");
    }

    @Test
    void lowerIsBetterDimensionsRankSmallestValueFirst(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.30, 20000, 250000, 150000, 500, 0.30));
        Path b = session(root, "2026_b", analysis(0.9, 0.4, 0.05, 30000, 180000, 90000, 400, 0.02));

        JsonNode dimensions = compare(List.of(a, b)).path("dimensions");
        JsonNode fraud = dimensions.get(1);

        assertThat(fraud.path("key").asText()).isEqualTo("fraud");
        assertThat(fraud.path("rankMetric").path("direction").asText()).isEqualTo("lower");
        // b 的欺诈支出占比更低 → rank 1
        assertThat(fraud.path("rows").get(0).path("name").asText()).isEqualTo("2026_b");
        assertThat(fraud.path("rows").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(fraud.path("rows").get(1).path("name").asText()).isEqualTo("2026_a");
        assertThat(fraud.path("rows").get(1).path("rank").asInt()).isEqualTo(2);
    }

    @Test
    void tiedValuesShareTheSameCompetitionRank(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.0, 0.5, 0.1, 20000, 200000, 100000, 500, 0.10));
        Path b = session(root, "2026_b", analysis(1.0, 0.5, 0.1, 20000, 200000, 100000, 500, 0.10));
        Path c = session(root, "2026_c", analysis(0.5, 0.2, 0.4, 40000, 200000, 50000, 500, 0.40));

        JsonNode negotiation = compare(List.of(a, b, c)).path("dimensions").get(0);

        assertThat(negotiation.path("rows").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(negotiation.path("rows").get(1).path("rank").asInt()).isEqualTo(1);
        assertThat(negotiation.path("rows").get(2).path("rank").asInt()).isEqualTo(3);
    }

    @Test
    void rankCrossingsCarryPrimaryAndSixDimensionRanks(@TempDir Path root) throws Exception {
        // a 主评分第一但欺诈维度最差 → 出现排名交叉
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.30, 20000, 250000, 150000, 500, 0.08));
        Path b = session(root, "2026_b", analysis(0.9, 0.4, 0.05, 30000, 180000, 90000, 400, 0.20));

        JsonNode crossings = compare(List.of(a, b)).path("rankCrossings");

        assertThat(crossings).hasSize(2);
        JsonNode first = crossings.get(0);
        assertThat(first.path("name").asText()).isEqualTo("2026_a");
        assertThat(first.path("primary").asInt()).isEqualTo(1);
        assertThat(first.path("ranks").path("negotiation").asInt()).isEqualTo(1);
        assertThat(first.path("ranks").path("fraud").asInt()).isEqualTo(2);
        assertThat(first.path("ranks").path("learning").isNull()).isTrue();
    }

    @Test
    void diagnosticsCarryChineseAnnotationsAndMeanValues(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode negotiation = compare(List.of(a)).path("dimensions").get(0);
        JsonNode diagnostics = negotiation.path("diagnostics");

        assertThat(diagnostics).isNotEmpty();
        JsonNode sePlus = diagnostics.get(0);
        assertThat(sePlus.path("label").asText()).isEqualTo("SE+");
        assertThat(sePlus.path("labelZh").asText()).isEqualTo("剩余效率");
        assertThat(sePlus.path("values").path("2026_a").asDouble()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void nestedBlocksExposeBandsFraudTypesAndReturnLayers(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode dimensions = compare(List.of(a)).path("dimensions");
        JsonNode efficiencyBlocks = dimensions.get(3).path("blocks");
        JsonNode fraudBlocks = dimensions.get(1).path("blocks");
        JsonNode executionBlocks = dimensions.get(4).path("blocks");

        assertThat(efficiencyBlocks.path("callsByBand").path("bargaining").path("count").asInt())
                .isEqualTo(6);
        assertThat(efficiencyBlocks.path("callsByBand").path("bargaining").path("share").asDouble())
                .isCloseTo(20.0, within(1e-9));
        assertThat(fraudBlocks.path("spendByFraudType").path("vip_fee").path("spend").asDouble())
                .isCloseTo(300.0, within(1e-9));
        assertThat(fraudBlocks.path("spendByFraudType").path("qty_bait").path("orders").asInt())
                .isZero();
        assertThat(executionBlocks.path("returnLayers").path("exp_return_rate_from_pricing").asDouble())
                .isCloseTo(0.06, within(1e-9));
        assertThat(executionBlocks.path("shipSpeed").path("standard").asInt()).isEqualTo(5);
    }

    @Test
    void averagesAcrossRunsOfTheSameSession(@TempDir Path root) throws Exception {
        Path dir = root.resolve("2026_multi");
        Path metrics = dir.resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(
                metrics.resolve("run_0_analysis.json"),
                analysis(1.0, 0.4, 0.1, 10000, 100000, 100000, 400, 0.10));
        Files.writeString(
                metrics.resolve("run_1_analysis.json"),
                analysis(2.0, 0.8, 0.3, 30000, 100000, 200000, 600, 0.30));

        ObjectNode node = compare(List.of(dir));

        assertThat(node.path("primaryScore").path("rows").get(0).path("value").asDouble())
                .isCloseTo(1.5, within(1e-9));
        assertThat(node.path("dimensions").get(0).path("rows").get(0).path("value").asDouble())
                .isCloseTo(0.6, within(1e-9));
    }

    @Test
    void missingFieldsBecomeNullValuesAndNullRanks(@TempDir Path root) throws Exception {
        Path bare = root.resolve("2026_bare");
        Files.createDirectories(bare.resolve("metrics"));
        Files.writeString(bare.resolve("metrics/run_0_analysis.json"), "{\"reward\":{}}");

        ObjectNode node = compare(List.of(bare));

        assertThat(node.path("primaryScore").path("rows").get(0).path("value").isNull()).isTrue();
        assertThat(node.path("primaryScore").path("rows").get(0).path("rank").isNull()).isTrue();
        assertThat(node.path("dimensions").get(0).path("rows").get(0).path("value").isNull()).isTrue();
        assertThat(node.path("rankCrossings").get(0).path("ranks").path("negotiation").isNull()).isTrue();
    }

    @Test
    void emptyInputStillEmitsSixDimensionsAndEmptyRows(@TempDir Path root) {
        ObjectNode node = compare(List.of());

        assertThat(node.path("primaryScore").path("rows")).isEmpty();
        assertThat(node.path("dimensions")).hasSize(6);
        assertThat(node.path("dimensions").get(0).path("rows")).isEmpty();
        assertThat(node.path("rankCrossings")).isEmpty();
        assertThat(node.path("learning")).isEmpty();
    }

    @Test
    void learningSectionIsNullFilledForLegacySessions(@TempDir Path root) throws Exception {
        Path a = session(root, "2026_a", analysis(1.5, 0.6, 0.1, 20000, 250000, 150000, 500, 0.08));

        JsonNode learning = compare(List.of(a)).path("learning").path("2026_a");

        assertThat(learning.isMissingNode()).isFalse();
        assertThat(learning.path("anchorRatio").isNull()).isTrue();
        assertThat(learning.path("zAnchor").isNull()).isTrue();
        assertThat(learning.path("episodes").asInt()).isZero();
        assertThat(learning.path("repeatPairs").asInt()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-app -am test -Dtest=DimensionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 DimensionService`

- [ ] **Step 3: 实现 DimensionService（骨架 + 主评分 + 维度值与排名）**

新建 `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DimensionService.java`：

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.web.DimensionAxis.Diagnostic;
import io.github.ecommercebench.evaluation.io.AnalysisJsonReader;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.model.ComparisonReport;
import io.github.ecommercebench.evaluation.model.ComparisonReport.SessionComparison;
import io.github.ecommercebench.evaluation.model.SessionLearning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 论文 §4.3 六维度视图的服务端计算：主评分（年末总资产倍数）、各维度排名指标值与 rank、rank crossings（附录 F.8）
 * 以及三类嵌套诊断块（八类活动带、五类欺诈支出、退货四层）。
 *
 * <p>刻意不复用 {@code RunComparator.METRIC_DEFS}：那份定义与 Python {@code compare_runs} 的比较表形状绑定，加行会破坏
 * 兼容性。本类直接读各 run 的 {@code analysis.json} 按论文口径重算，与 {@code CompareService} 并列、互不依赖。学习维度的值
 * 不在 analysis.json 内，取自阶段 A 挂在 {@link SessionComparison#learning()} 上的 {@link SessionLearning}。
 *
 * <p>取值口径：会话级为各 run 均值，null run 跳过不计入分母（与 {@code RunComparator} 一致）；rank 1 表示该维度最好，
 * 并列取 competition ranking（1,2,2,4），value 为 null 的会话排末位且 rank 为 null。
 */
public final class DimensionService {

    private static final Pattern ANALYSIS_FILE = Pattern.compile("run_(\\d+)_analysis\\.json");

    /** 论文 §E.2 的五类欺诈型，固定键序（未出现的补 0）。 */
    private static final List<String> FRAUD_TYPES =
            List.of("fake_urgency", "future_discount", "qty_bait", "quality_downgrade", "vip_fee");

    /** 论文 §E.4 的八类活动带，固定键序（与 ActivityBands.BAND_KEYS 一致）。 */
    private static final List<String> BANDS =
            List.of(
                    "waiting", "shipping", "listing", "withdraw",
                    "bargaining", "state_polling", "memory", "remainder");

    /** 论文 §E.5 的退货四层，固定键序。 */
    private static final List<String> RETURN_LAYERS =
            List.of(
                    "exp_return_rate_natural",
                    "exp_return_rate_from_pricing",
                    "exp_return_rate_from_ship_speed",
                    "exp_return_rate_from_defective_fraud");

    private static final List<String> SHIP_SPEEDS = List.of("fast", "standard", "slow");

    private final ObjectMapper mapper;
    private final AnalysisJsonReader reader = new AnalysisJsonReader();
    private final RunLayoutResolver resolver = new RunLayoutResolver();

    public DimensionService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 产出四段 JSON：{@code primaryScore}、{@code dimensions}、{@code rankCrossings}、{@code learning}。
     *
     * @param sessionDirs 会话目录，与 {@code report.sessions()} 同序同长
     */
    public ObjectNode node(List<Path> sessionDirs, ComparisonReport report) {
        List<String> names = new ArrayList<>(sessionDirs.size());
        List<List<JsonNode>> runsPerSession = new ArrayList<>(sessionDirs.size());
        for (Path dir : sessionDirs) {
            names.add(dir.getFileName().toString());
            runsPerSession.add(loadRuns(dir));
        }

        ObjectNode root = mapper.createObjectNode();
        Map<String, Double> primary = primaryScores(names, runsPerSession);
        Map<String, Integer> primaryRanks = rank(primary, false);
        root.set("primaryScore", primaryNode(names, primary, primaryRanks, runsPerSession));

        Map<String, Map<String, Double>> dimensionValues = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> dimensionRanks = new LinkedHashMap<>();
        ArrayNode dimensions = root.putArray("dimensions");
        for (DimensionAxis axis : DimensionAxis.values()) {
            Map<String, Double> values = dimensionValues(axis, names, runsPerSession, report);
            Map<String, Integer> ranks = rank(values, axis.lowerIsBetter());
            dimensionValues.put(axis.key(), values);
            dimensionRanks.put(axis.key(), ranks);
            dimensions.add(dimensionNode(axis, names, values, ranks, runsPerSession));
        }

        root.set("rankCrossings", crossingsNode(names, primaryRanks, dimensionRanks));
        root.set("learning", learningNode(names, report));
        return root;
    }
```

接着是各私有方法（同文件继续）：

```java
    /** 列出该会话全部 run 的 analysis.json，按 run 序号升序。 */
    private List<JsonNode> loadRuns(Path sessionDir) {
        Path metrics = resolver.resolve(sessionDir).metricsDir();
        if (!Files.isDirectory(metrics)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(metrics)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> ANALYSIS_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(path -> runIndexOf(path.getFileName().toString())))
                    .map(reader::read)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("无法列出 analysis 文件: " + metrics, exception);
        }
    }

    private static int runIndexOf(String fileName) {
        Matcher matcher = ANALYSIS_FILE.matcher(fileName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 主评分 = reward.final_score 各 run 均值（论文 §3.7：年末总资产 ÷ 初始资本的资产倍数）。 */
    private static Map<String, Double> primaryScores(
            List<String> names, List<List<JsonNode>> runsPerSession) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            List<Double> samples = new ArrayList<>();
            for (JsonNode run : runsPerSession.get(i)) {
                Double score = DimensionAxis.at(run, "reward", "final_score");
                if (score != null) {
                    samples.add(score);
                }
            }
            values.put(names.get(i), meanOrNull(samples));
        }
        return values;
    }
```

继续（均值、维度值、排名）：

```java
    /** 各 run 均值；null 样本跳过，全 null 返回 null。 */
    private static Double meanOrNull(List<Double> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (double sample : samples) {
            sum += sample;
        }
        return sum / samples.size();
    }

    /**
     * 某维度在各会话上的值：analysis.json 能算的五个维度按 run 均值；学习维度取 {@link SessionLearning#anchorRatio()}
     * （它已是阶段 A 按「各 episode 比率的均值」聚合好的会话级值，不再二次平均）。
     */
    private static Map<String, Double> dimensionValues(
            DimensionAxis axis,
            List<String> names,
            List<List<JsonNode>> runsPerSession,
            ComparisonReport report) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            if (axis == DimensionAxis.LEARNING) {
                SessionComparison session = report.sessions().get(i);
                SessionLearning learning = session.learning();
                values.put(names.get(i), learning == null ? null : learning.anchorRatio());
                continue;
            }
            List<Double> samples = new ArrayList<>();
            for (JsonNode run : runsPerSession.get(i)) {
                Double raw = axis.raw(run);
                if (raw != null) {
                    samples.add(raw);
                }
            }
            values.put(names.get(i), meanOrNull(samples));
        }
        return values;
    }

    /**
     * 按维度方向排名：rank 1 = 最好；并列取 competition ranking（1,2,2,4）；null 值不入榜（map 中不出现该键）。
     */
    private static Map<String, Integer> rank(Map<String, Double> values, boolean lowerIsBetter) {
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (entry.getValue() != null) {
                scored.add(entry);
            }
        }
        scored.sort(
                (x, y) ->
                        lowerIsBetter
                                ? Double.compare(x.getValue(), y.getValue())
                                : Double.compare(y.getValue(), x.getValue()));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        Double previous = null;
        int previousRank = 0;
        for (int i = 0; i < scored.size(); i++) {
            Map.Entry<String, Double> entry = scored.get(i);
            int rank = previous != null && previous.doubleValue() == entry.getValue().doubleValue()
                    ? previousRank
                    : i + 1;
            ranks.put(entry.getKey(), rank);
            previous = entry.getValue();
            previousRank = rank;
        }
        return ranks;
    }
```

- [ ] **Step 4: 实现四个 JSON 组装方法与三个诊断块**

同文件继续追加：

```java
    /** 主评分段：标签、单位、取值来源字段，以及每会话的倍数、rank、年末总资产均值、破产 run 数与终止原因。 */
    private ObjectNode primaryNode(
            List<String> names,
            Map<String, Double> values,
            Map<String, Integer> ranks,
            List<List<JsonNode>> runsPerSession) {
        ObjectNode node = mapper.createObjectNode();
        node.put("labelZh", "年末总资产倍数");
        node.put("unit", "×");
        node.put("sourceField", "reward.final_score");
        ArrayNode rows = node.putArray("rows");
        for (String name : orderByRank(names, ranks)) {
            ObjectNode row = rows.addObject();
            row.put("name", name);
            putNumber(row, "value", values.get(name));
            putInteger(row, "rank", ranks.get(name));
            List<JsonNode> runs = runsPerSession.get(names.indexOf(name));
            putNumber(row, "finalBalance", meanOfRuns(runs, "profitability", "final_balance"));
            row.put("bankruptRuns", countTrue(runs, "profitability", "bankrupt"));
            putText(row, "termination", lastTermination(runs));
        }
        return node;
    }

    /** 各 run 在给定 path 上的数值均值；null 跳过，全 null 返回 null。 */
    private static Double meanOfRuns(List<JsonNode> runs, String... path) {
        List<Double> samples = new ArrayList<>();
        for (JsonNode run : runs) {
            Double value = DimensionAxis.at(run, path);
            if (value != null) {
                samples.add(value);
            }
        }
        return meanOrNull(samples);
    }

    /** 统计各 run 中该布尔字段为 true 的个数（破产 run 数，论文 §3.7：被截断的 run 仍计入均值）。 */
    private static int countTrue(List<JsonNode> runs, String... path) {
        int count = 0;
        for (JsonNode run : runs) {
            Double value = DimensionAxis.at(run, path);
            if (value != null && value == 1.0) {
                count++;
            }
        }
        return count;
    }

    /** 最后一个 run 的终止原因（reward.termination_reason）；无 run 或缺字段时返回 null。 */
    private static String lastTermination(List<JsonNode> runs) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            JsonNode reason = runs.get(i).path("reward").path("termination_reason");
            if (reason.isTextual()) {
                return reason.asText();
            }
        }
        return null;
    }

    /** 写入可空文本字段：null 时写 JSON null，保证前端形状稳定。 */
    private static void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /** 单个维度对象：口径元信息 + 排名指标 + 带 rank 的行 + 诊断指标 + 嵌套诊断块。 */
    private ObjectNode dimensionNode(
            DimensionAxis axis,
            List<String> names,
            Map<String, Double> values,
            Map<String, Integer> ranks,
            List<List<JsonNode>> runsPerSession) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", axis.key());
        node.put("labelZh", axis.labelZh());
        node.put("paperRef", axis.paperRef());
        ObjectNode rankMetric = node.putObject("rankMetric");
        rankMetric.put("label", axis.rankLabel());
        rankMetric.put("labelZh", axis.rankLabelZh());
        rankMetric.put("direction", axis.lowerIsBetter() ? "lower" : "higher");
        rankMetric.put("unit", axis.unit());

        ArrayNode rows = node.putArray("rows");
        for (String name : orderByRank(names, ranks)) {
            ObjectNode row = rows.addObject();
            row.put("name", name);
            putNumber(row, "value", values.get(name));
            putInteger(row, "rank", ranks.get(name));
        }

        ArrayNode diagnostics = node.putArray("diagnostics");
        for (Diagnostic diagnostic : axis.diagnostics()) {
            ObjectNode item = diagnostics.addObject();
            item.put("label", diagnostic.label());
            item.put("labelZh", diagnostic.labelZh());
            ObjectNode perSession = item.putObject("values");
            for (int i = 0; i < names.size(); i++) {
                List<Double> samples = new ArrayList<>();
                for (JsonNode run : runsPerSession.get(i)) {
                    Double value = DimensionAxis.at(run, diagnostic.path().toArray(String[]::new));
                    if (value != null) {
                        samples.add(value);
                    }
                }
                putNumber(perSession, names.get(i), meanOrNull(samples));
            }
        }

        node.set("blocks", blocksNode(axis, runsPerSession));
        return node;
    }

    /**
     * 三个嵌套诊断块（汇总口径：值为全部选中会话的全部 run 的均值，逐会话明细由 diagnostics 承担）：
     * 效率→八类活动带，欺诈→五类骗局支出，执行→退货四层与发货速度构成。
     */
    private ObjectNode blocksNode(DimensionAxis axis, List<List<JsonNode>> runsPerSession) {
        ObjectNode blocks = mapper.createObjectNode();
        switch (axis) {
            case EFFICIENCY -> blocks.set(
                    "callsByBand",
                    keyedBlock(
                            runsPerSession,
                            BANDS,
                            List.of("operational_efficiency", "calls_by_band"),
                            List.of("operational_efficiency", "calls_by_band_share")));
            case FRAUD -> blocks.set(
                    "spendByFraudType",
                    objectBlock(
                            runsPerSession,
                            FRAUD_TYPES,
                            List.of("fraud_identification", "spend_by_fraud_type"),
                            List.of("orders", "units", "spend")));
            case EXECUTION -> {
                blocks.set(
                        "returnLayers",
                        flatBlock(runsPerSession, RETURN_LAYERS, List.of("return_management")));
                blocks.set(
                        "shipSpeed",
                        flatBlock(
                                runsPerSession,
                                SHIP_SPEEDS,
                                List.of("fulfilment_quality", "ship_speed_counts")));
            }
            default -> {
                // 谈判/偿付/学习三个维度没有嵌套块，diagnostics 已覆盖
            }
        }
        return blocks;
    }

    /**
     * 「计数 + 占比」双字段汇总块：每个键输出 {@code {count, share}}，值为全部选中会话的全部 run 的均值。
     * 用于八类活动带（{@code calls_by_band} 与 {@code calls_by_band_share}）；逐会话明细由 diagnostics 承担。
     */
    private ObjectNode keyedBlock(
            List<List<JsonNode>> runsPerSession,
            List<String> keys,
            List<String> countPath,
            List<String> sharePath) {
        ObjectNode block = mapper.createObjectNode();
        for (String key : keys) {
            List<Double> counts = new ArrayList<>();
            List<Double> shares = new ArrayList<>();
            for (List<JsonNode> runs : runsPerSession) {
                for (JsonNode run : runs) {
                    Double count = DimensionAxis.at(run, withKey(countPath, key));
                    Double share = DimensionAxis.at(run, withKey(sharePath, key));
                    if (count != null) {
                        counts.add(count);
                    }
                    if (share != null) {
                        shares.add(share);
                    }
                }
            }
            ObjectNode entry = block.putObject(key);
            putNumber(entry, "count", meanOrNull(counts));
            putNumber(entry, "share", meanOrNull(shares));
        }
        return block;
    }
```

同文件继续追加（另两个诊断块与其余方法）：

```java
    /** 嵌套对象块：每个键输出其子字段（如 orders/units/spend）的跨会话均值；缺失键补 0。 */
    private ObjectNode objectBlock(
            List<List<JsonNode>> runsPerSession,
            List<String> keys,
            List<String> parentPath,
            List<String> fields) {
        ObjectNode block = mapper.createObjectNode();
        for (String key : keys) {
            ObjectNode entry = block.putObject(key);
            for (String field : fields) {
                List<Double> samples = new ArrayList<>();
                for (List<JsonNode> runs : runsPerSession) {
                    for (JsonNode run : runs) {
                        Double value =
                                DimensionAxis.at(
                                        run,
                                        concat(parentPath, List.of(key, field)));
                        if (value != null) {
                            samples.add(value);
                        }
                    }
                }
                // 论文 §E.2 要求五类骗局全部出现，未记录的补 0（与 MetricsJsonWriter 预置全键一致）
                putNumber(entry, field, samples.isEmpty() ? 0.0 : meanOrNull(samples));
            }
        }
        return block;
    }

    /** 平铺块：父路径下每个键的跨会话均值；缺失键补 0。 */
    private ObjectNode flatBlock(
            List<List<JsonNode>> runsPerSession, List<String> keys, List<String> parentPath) {
        ObjectNode block = mapper.createObjectNode();
        for (String key : keys) {
            List<Double> samples = new ArrayList<>();
            for (List<JsonNode> runs : runsPerSession) {
                for (JsonNode run : runs) {
                    Double value = DimensionAxis.at(run, concat(parentPath, List.of(key)));
                    if (value != null) {
                        samples.add(value);
                    }
                }
            }
            putNumber(block, key, samples.isEmpty() ? 0.0 : meanOrNull(samples));
        }
        return block;
    }

    /** rank crossings（附录 F.8）：每会话一行，含主评分 rank 与六维度 rank。 */
    private ArrayNode crossingsNode(
            List<String> names,
            Map<String, Integer> primaryRanks,
            Map<String, Map<String, Integer>> dimensionRanks) {
        ArrayNode array = mapper.createArrayNode();
        List<String> ordered = new ArrayList<>(names);
        ordered.sort(
                Comparator.comparingInt(
                        name -> primaryRanks.getOrDefault(name, Integer.MAX_VALUE)));
        for (String name : ordered) {
            ObjectNode row = array.addObject();
            row.put("name", name);
            putInteger(row, "primary", primaryRanks.get(name));
            ObjectNode ranks = row.putObject("ranks");
            for (DimensionAxis axis : DimensionAxis.values()) {
                putInteger(ranks, axis.key(), dimensionRanks.get(axis.key()).get(name));
            }
        }
        return array;
    }

    /** 学习段：逐会话输出阶段 A 的 SessionLearning；无产物时全 null、计数 0。 */
    private ObjectNode learningNode(List<String> names, ComparisonReport report) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < names.size(); i++) {
            SessionLearning learning = report.sessions().get(i).learning();
            ObjectNode entry = node.putObject(names.get(i));
            putNumber(entry, "anchorRatio", learning == null ? null : learning.anchorRatio());
            putNumber(entry, "anchorRegret", learning == null ? null : learning.anchorRegret());
            putNumber(entry, "newLow", learning == null ? null : learning.newLow());
            putNumber(entry, "newLowRatioZ", learning == null ? null : learning.newLowRatioZ());
            putNumber(entry, "zAnchor", learning == null ? null : learning.zAnchor());
            putNumber(entry, "seHalfLift", learning == null ? null : learning.seHalfLift());
            putNumber(entry, "seTrend", learning == null ? null : learning.seTrend());
            putNumber(entry, "fraudHalfLift", learning == null ? null : learning.fraudHalfLift());
            entry.put("episodes", learning == null ? 0 : learning.episodes());
            entry.put("repeatPairs", learning == null ? 0 : learning.repeatPairs());
            entry.put("reorders", learning == null ? 0 : learning.reorders());
        }
        return node;
    }

    /** 按 rank 升序排列会话名；无 rank（值为 null）的排在最后，保持输入序。 */
    private static List<String> orderByRank(List<String> names, Map<String, Integer> ranks) {
        List<String> ordered = new ArrayList<>(names);
        Set<String> ranked = new LinkedHashSet<>(ranks.keySet());
        ordered.sort(
                (x, y) -> {
                    boolean hasX = ranked.contains(x);
                    boolean hasY = ranked.contains(y);
                    if (hasX != hasY) {
                        return hasX ? -1 : 1;
                    }
                    return hasX ? Integer.compare(ranks.get(x), ranks.get(y)) : 0;
                });
        return ordered;
    }

    private static String[] withKey(List<String> path, String key) {
        return concat(path, List.of(key));
    }

    private static String[] concat(List<String> prefix, List<String> suffix) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(suffix);
        return all.toArray(String[]::new);
    }

    private static void putNumber(ObjectNode node, String field, Double value) {
        if (value == null || Double.isNaN(value)) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putInteger(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.intValue());
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl ecbench-app -am test -Dtest=DimensionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（11 tests）

- [ ] **Step 6: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/DimensionService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DimensionServiceTest.java
git commit -m "feat(web): compute paper dimension views, ranks and diagnostic blocks"
```

---

### Task 4: 把四段新契约接进 /api/compare

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/CompareService.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/CompareServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `DimensionService(ObjectMapper).node(List<Path>, ComparisonReport) → ObjectNode`（含 `primaryScore`/`dimensions`/`rankCrossings`/`learning` 四段）
- Produces:
  - `CompareService(ObjectMapper mapper)`（既有单参构造保留，内部用真实 `DimensionService`）
  - `CompareService(ObjectMapper mapper, DimensionService dimensions)`（测试注入点）
  - `compare(List<Path>) → JsonNode`：既有 `sessions`/`radar`/`ranking` 三段**形状与取值不变**，追加上述四段（用 `fieldNames()` 合并，避免在两处硬编码段名）

- [ ] **Step 1: 追加失败测试**

在 `CompareServiceTest` 的 `emptyInputYieldsEmptyShape` 之后、`find(...)` 助手之前插入：

```java
    @Test
    void appendsPaperDimensionSectionsWithoutTouchingLegacyOnes(@TempDir Path log) throws Exception {
        Path a = log.resolve("2026_a-model");
        Path b = log.resolve("2026_b-model");
        session(a, 90000);
        session(b, 120000);

        JsonNode node = service.compare(List.of(a, b));

        // 既有三段不变
        assertThat(node.path("sessions")).hasSize(2);
        assertThat(node.path("radar").path("axes")).hasSize(7);
        assertThat(node.path("ranking").get(0).path("name").asText()).isEqualTo("2026_b-model");
        // 新增四段
        assertThat(node.path("primaryScore").path("labelZh").asText()).isEqualTo("年末总资产倍数");
        assertThat(node.path("primaryScore").path("rows")).hasSize(2);
        assertThat(node.path("dimensions")).hasSize(6);
        assertThat(node.path("rankCrossings")).hasSize(2);
        assertThat(node.path("learning").path("2026_a-model")).isNotNull();
        assertThat(node.path("learning").path("2026_a-model").path("anchorRatio").isNull()).isTrue();
    }

    @Test
    void dimensionsSurviveEmptyInput(@TempDir Path log) {
        JsonNode node = service.compare(List.of());

        assertThat(node.path("dimensions")).hasSize(6);
        assertThat(node.path("dimensions").get(0).path("key").asText()).isEqualTo("negotiation");
        assertThat(node.path("primaryScore").path("rows")).isEmpty();
        assertThat(node.path("rankCrossings")).isEmpty();
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-app -am test -Dtest=CompareServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— 两个新测试因 `primaryScore`/`dimensions` 等段缺失（`path(...)` 为 MissingNode）而失败

- [ ] **Step 3: 实现**

`CompareService.java` 三处改动：

① 字段与构造器（替换既有单一构造器）：

```java
    private final ObjectMapper mapper;
    private final DimensionService dimensions;

    public CompareService(ObjectMapper mapper) {
        this(mapper, new DimensionService(mapper));
    }

    public CompareService(ObjectMapper mapper, DimensionService dimensions) {
        this.mapper = mapper;
        this.dimensions = dimensions;
    }
```

② `compare` 方法替换为：

```java
    /**
     * 对给定会话目录依次输出聚合指标、归一化雷达、排名，以及论文 §4.3 的维度视图四段；空输入返回同形的空结构。
     */
    public JsonNode compare(List<Path> sessionDirs) {
        ComparisonReport report = new RunComparator().compare(sessionDirs);
        NormalizedProfile profile = new CrossModelProfiler().profile(report);
        ObjectNode root = mapper.createObjectNode();
        root.set("sessions", sessionsNode(report));
        root.set("radar", radarNode(profile));
        root.set("ranking", rankingNode(new RankingTable().rank(profile)));
        ObjectNode paper = dimensions.node(sessionDirs, report);
        paper.fieldNames().forEachRemaining(name -> root.set(name, paper.get(name)));
        return root;
    }
```

③ 类级 Javadoc 追加一句（放在既有第二段之后）：

```
 * <p>另外追加论文 §3.7/§4.3 的维度视图四段（{@code primaryScore}/{@code dimensions}/{@code rankCrossings}/
 * {@code learning}），由 {@link DimensionService} 计算；既有三段的形状与取值保持不变，以免破坏前端与回归测试。
```

- [ ] **Step 4: 运行 app 模块全部测试确认通过**

Run: `mvn -q -pl ecbench-app -am test`
Expected: PASS，0 failures、0 errors（含 `DashboardServerTest` 的既有端到端断言）

- [ ] **Step 5: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/CompareService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/CompareServiceTest.java
git commit -m "feat(web): serve paper dimension sections from /api/compare"
```

---

### Task 5: chatbox 供应商欺诈标注与 `serve --catalog`

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/RunArtifactService.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/RunArtifactServiceTest.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/cli/ServeCommandCatalogTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的 `SupplierRosterReader.RosterEntry` / `readByEmail(Path)`、`app.config.ResourcePaths.of(Path).optional(String)` 与 `.dataDir(null)`
- Produces:
  - `RunArtifactService(ObjectMapper mapper)`（保留，等价于空名册）与 `RunArtifactService(ObjectMapper mapper, Map<String, SupplierRosterReader.RosterEntry> roster)`
  - `chatbox(Path, int)` 的每个 `suppliers[]` 元素新增 `supplierType`/`personality`/`fraudType`（名册未命中 → 三者均为 JSON null）；其余字段与既有形状不变
  - `DashboardServer(Path logDir, String host, int port)`（保留，空名册）与 `DashboardServer(Path logDir, String host, int port, Map<String, SupplierRosterReader.RosterEntry> roster)`
  - `ServeCommand.resolveCatalog(String override, Path workingDir) → Path`（package-private static，供直接单测）
  - `serve` 新增选项 `--catalog`（默认 null → 资源根 `data/suppliers.csv`）

- [ ] **Step 1: 写失败测试（标注行为）**

在 `RunArtifactServiceTest` 中追加两个测试（放在既有 chatbox 测试之后）。该文件已有 `@TempDir` 与写 `trajectories/run_0_messages.jsonl` 的写法，沿用其 fixture 构造方式：

```java
    @Test
    void chatboxAnnotatesSupplierFromRoster(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("trajectories"));
        String call =
                "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"tc1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"chatbox\",\"arguments\":\"{\\\"uid\\\":\\\"bad@a.com\\\"}\"}}]}";
        String reply =
                "{\"role\":\"tool\",\"tool_call_id\":\"tc1\","
                        + "\"content\":\"{\\\"message\\\":\\\"message_sent\\\"}\"}";
        Files.writeString(
                session.resolve("trajectories/run_0_messages.jsonl"), call + "\n" + reply + "\n");

        Map<String, SupplierRosterReader.RosterEntry> roster =
                Map.of(
                        "bad@a.com",
                        new SupplierRosterReader.RosterEntry(
                                "bad@a.com", "Cedar Distribution", "bad", "Tough", "vip_fee"));
        RunArtifactService annotated = new RunArtifactService(new ObjectMapper(), roster);

        JsonNode supplier = annotated.chatbox(session, 0).path("suppliers").get(0);

        assertThat(supplier.path("supplier").asText()).isEqualTo("bad@a.com");
        assertThat(supplier.path("supplierType").asText()).isEqualTo("bad");
        assertThat(supplier.path("personality").asText()).isEqualTo("Tough");
        assertThat(supplier.path("fraudType").asText()).isEqualTo("vip_fee");
    }

    @Test
    void chatboxWithoutRosterMatchEmitsNullAnnotations(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("trajectories"));
        String call =
                "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"tc1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"chatbox\",\"arguments\":\"{\\\"uid\\\":\\\"unknown@a.com\\\"}\"}}]}";
        String reply =
                "{\"role\":\"tool\",\"tool_call_id\":\"tc1\","
                        + "\"content\":\"{\\\"message\\\":\\\"message_sent\\\"}\"}";
        Files.writeString(
                session.resolve("trajectories/run_0_messages.jsonl"), call + "\n" + reply + "\n");

        JsonNode supplier = service.chatbox(session, 0).path("suppliers").get(0);

        assertThat(supplier.path("supplierType").isNull()).isTrue();
        assertThat(supplier.path("personality").isNull()).isTrue();
        assertThat(supplier.path("fraudType").isNull()).isTrue();
        // 既有字段不受影响
        assertThat(supplier.path("messages")).hasSize(2);
    }
```

该测试文件顶部按需补：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader;
import java.util.Map;
```

- [ ] **Step 2: 写失败测试（名册路径解析）**

新建 `ecbench-app/src/test/java/io/github/ecommercebench/app/cli/ServeCommandCatalogTest.java`：

```java
package io.github.ecommercebench.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** serve 的供应商名册路径解析测试：--catalog 覆盖优先，否则回退到资源根 data/suppliers.csv。 */
class ServeCommandCatalogTest {

    @Test
    void defaultsToDataDirUnderWorkingDir(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));

        Path catalog = ServeCommand.resolveCatalog(null, workingDir);

        assertThat(catalog).isEqualTo(workingDir.resolve("data/suppliers.csv"));
    }

    @Test
    void explicitOverrideWins(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));
        Path custom = workingDir.resolve("elsewhere/roster.csv");

        assertThat(ServeCommand.resolveCatalog(custom.toString(), workingDir)).isEqualTo(custom);
    }

    @Test
    void blankOverrideIsIgnored(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("data"));

        assertThat(ServeCommand.resolveCatalog("   ", workingDir))
                .isEqualTo(workingDir.resolve("data/suppliers.csv"));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -q -pl ecbench-app -am test -Dtest='RunArtifactServiceTest,ServeCommandCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 方法 resolveCatalog(String,Path)` 与 `构造器 RunArtifactService 无法应用到给定类型`

- [ ] **Step 4: 实现 RunArtifactService 标注**

`RunArtifactService.java` 四处改动：

① 导入区补两行（`java.nio.file.Path` 之后、`java.util.Map` 之前保持字母序）：

```java
import io.github.ecommercebench.evaluation.io.SupplierRosterReader;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;
import java.util.Locale;
```

② 字段与构造器（替换既有单一构造器）：

```java
    private final ObjectMapper mapper;
    private final Map<String, RosterEntry> roster;

    public RunArtifactService(ObjectMapper mapper) {
        this(mapper, Map.of());
    }

    public RunArtifactService(ObjectMapper mapper, Map<String, RosterEntry> roster) {
        this.mapper = mapper;
        this.roster = roster;
    }
```

③ `chatbox(...)` 的循环体在 `node.put("supplier", ...)` 之后插入标注：

```java
        for (ChatboxConversation conversation : conversations.values()) {
            ObjectNode node = suppliers.addObject();
            node.put("supplier", conversation.supplier());
            RosterEntry entry = roster.get(conversation.supplier().toLowerCase(Locale.ROOT));
            putText(node, "supplierType", entry == null ? null : entry.type());
            putText(node, "personality", entry == null ? null : entry.personality());
            putText(node, "fraudType", entry == null ? null : entry.fraudType());
            ArrayNode messages = node.putArray("messages");
            for (String raw : conversation.rawLines()) {
                appendMessage(messages, raw);
            }
        }
```

④ 在 `appendMessage` 之后新增助手，并更新类级 Javadoc：

```java
    /** 写入可空文本字段：null 时写 JSON null（而非省略字段），保证前端形状稳定。 */
    private static void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
```

类级 Javadoc 追加一句：

```
 * <p>chatbox 的每个供应商另外附带名册标注（{@code supplierType}/{@code personality}/{@code fraudType}），
 * 供前端按论文附录 G 的方式标出欺诈类型；名册缺失或未命中时三字段为 JSON null，不影响既有形状。
```

- [ ] **Step 5: 实现 DashboardServer 与 ServeCommand**

`DashboardServer.java`：导入区补 `import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;` 与 `import java.util.Map;`，构造器替换为：

```java
    public DashboardServer(Path logDir, String host, int port) throws IOException {
        this(logDir, host, port, Map.of());
    }

    public DashboardServer(Path logDir, String host, int port, Map<String, RosterEntry> roster)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ApiRouter api =
                new ApiRouter(
                        mapper,
                        new SessionScanner(logDir),
                        new CompareService(mapper),
                        new RunArtifactService(mapper, roster));
        // 先绑定端口再建线程池：绑定失败（如端口被占）时不会泄漏已创建的工作线程。
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS);
        this.httpServer.createContext("/api", api);
        this.httpServer.createContext("/", new StaticAssets());
        this.httpServer.setExecutor(this.executor);
    }
```

`ServeCommand.java` 四处改动：

① 导入区补：

```java
import io.github.ecommercebench.app.config.ResourcePaths;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;
import java.util.Map;
```

② 新增选项（放在 `--host` 之后）：

```java
    @Option(
            names = "--catalog",
            description = "供应商名册 CSV（用于 chatbox 欺诈标注），默认取资源根 data/suppliers.csv")
    private String catalog;
```

③ `call()` 中把 `try (DashboardServer server = new DashboardServer(root, host, port)) {` 改为：

```java
        Map<String, RosterEntry> roster = loadRoster();
        try (DashboardServer server = new DashboardServer(root, host, port, roster)) {
```

并在 `resolveLogDir()` 之后新增两个方法：

```java
    /** 读取供应商名册；不可用时返回空 map（chatbox 降级为无标注，服务照常启动）。 */
    private Map<String, RosterEntry> loadRoster() {
        Path catalogCsv = resolveCatalog(catalog, Path.of(System.getProperty("user.dir")));
        Map<String, RosterEntry> roster = new SupplierRosterReader().readByEmail(catalogCsv);
        if (roster.isEmpty()) {
            System.err.println("供应商名册不可用，chatbox 不显示欺诈标注: " + catalogCsv);
        }
        return roster;
    }

    /** 名册路径解析：{@code --catalog} 覆盖优先，否则回退到资源根 {@code data/suppliers.csv}。 */
    static Path resolveCatalog(String override, Path workingDir) {
        ResourcePaths paths = ResourcePaths.of(workingDir);
        Path custom = paths.optional(override);
        return custom != null ? custom : paths.dataDir(null).resolve("suppliers.csv");
    }
```

④ 类级 Javadoc 追加一句：

```
 * <p>{@code --catalog} 仅用于给 chatbox 谈判记录标注供应商类型/人格/欺诈类型（论文附录 G 的阅读方式）；名册缺失时
 * 自动降级为无标注，不影响启动，也不引入新的路径解析入口（该路径不参与 {@code {name}} 的安全校验）。
```

- [ ] **Step 6: 运行 app 模块全部测试确认通过**

Run: `mvn -q -pl ecbench-app -am test`
Expected: PASS，0 failures、0 errors

- [ ] **Step 7: 用真实数据端到端冒烟**

```bash
mvn -o -q -DskipTests package
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir log --port 8098 &
sleep 3
curl -s "http://127.0.0.1:8098/api/sessions/20261010_173016_qwen3.7-plus/chatbox?run=0" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print([(s['supplier'], s['supplierType'], s['fraudType']) for s in d['suppliers']])"
curl -s -o /dev/null -w "compare=%{http_code}\n" "http://127.0.0.1:8098/api/compare"
kill %1
```

Expected: 打印出的每个供应商都带 `good`/`bad` 与（欺诈者的）具体 `fraud_type`，例如 `('oaklink.sup-0238@wholesale.com', 'good', None)`；`compare=200`。若全部为 `None`，检查 `--catalog` 默认解析到的路径下 `suppliers.csv` 是否存在（`ResourcePaths` 优先 `java-impl/data`，本仓库无该目录故回退仓库根 `data/`）。

- [ ] **Step 8: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/RunArtifactService.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/RunArtifactServiceTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/cli/ServeCommandCatalogTest.java
git commit -m "feat(web): annotate chatbox suppliers with roster fraud type via serve --catalog"
```

---

### Task 6: 浅色主题（只换配色，版式不动）

**Files:**
- Modify: `ecbench-app/src/main/resources/web/app.css`（`:root` 12 个变量 + 10 处硬编码色）
- Modify: `ecbench-app/src/main/resources/web/app.js`（`PALETTE` 常量 + 余额曲线数据点描边色）

**Interfaces:**
- Produces: 前端 CSS 变量契约（后续任务的新面板一律只用这些变量，不得再写死颜色）：
  `--bg #f5f6f8`、`--bg-card #ffffff`、`--bg-inset #eef1f5`、`--line #d7dce3`、`--line-soft #e6e9ee`、`--ink #1b2430`、`--ink-dim #55606e`、`--ink-faint #8a94a2`、`--amber #b0761a`、`--cyan #26808f`、`--green #3d8b50`、`--red #bd4136`
  以及 JS 侧常量：`PALETTE`（8 色）、`GRID = '#e6e9ee'`、`AXIS = '#8a94a2'`、`LABEL = '#55606e'`、`POINT_RING = '#ffffff'`

- [ ] **Step 1: 替换 `:root` 调色板**

把 `app.css` 的 `:root { ... }` 整块（L4-19）替换为：

```css
:root {
  --bg: #f5f6f8;
  --bg-card: #ffffff;
  --bg-inset: #eef1f5;
  --line: #d7dce3;
  --line-soft: #e6e9ee;
  --ink: #1b2430;
  --ink-dim: #55606e;
  --ink-faint: #8a94a2;
  --amber: #b0761a;
  --cyan: #26808f;
  --green: #3d8b50;
  --red: #bd4136;
  --display: "Iowan Old Style", "Palatino Linotype", "Bodoni 72", Georgia, serif;
  --mono: "SF Mono", Menlo, Monaco, "Cascadia Mono", "Roboto Mono", monospace;
  --rail: 264px;
}
```

- [ ] **Step 2: 逐处替换 19 个硬编码颜色（已逐行盘点，按「现值 → 新值」定位，行号仅供参照）**

| # | 位置（选择器） | 现值 | 新值 |
|---|---|---|---|
| 1 | `body` 背景 radial-gradient 第 1 层（L28） | `rgba(240, 166, 60, .07)` | `rgba(176, 118, 26, .05)` |
| 2 | `body` 背景 radial-gradient 第 2 层（L29） | `rgba(87, 198, 214, .06)` | `rgba(38, 128, 143, .04)` |
| 3 | `body` 两层 repeating-linear-gradient（L30、L31，共 2 处） | `rgba(255,255,255,.014)` | `rgba(16,24,40,.03)` |
| 4 | `.rail` 背景 linear-gradient（L66） | `rgba(255,255,255,.022)` | `rgba(255,255,255,.6)` |
| 5 | `.brand-mark` 的 `box-shadow`（L80） | `inset 0 0 18px rgba(240,166,60,.16)` | `inset 0 0 0 1px rgba(176,118,26,.22)` |
| 6 | `.session:hover` 的 `background`（L110） | `rgba(255,255,255,.035)` | `rgba(16,24,40,.04)` |
| 7 | `.session.on` 的 `background`（L111） | `rgba(240,166,60,.07)` | `rgba(176,118,26,.08)` |
| 8 | `button:hover` 的 `background`（L131） | `rgba(240,166,60,.08)` | `rgba(176,118,26,.10)` |
| 9 | `.dot.live` 的 `box-shadow`（L140） | `rgba(130,201,143,.16)` | `rgba(61,139,80,.18)` |
| 10 | `.dot.bad` 的 `box-shadow`（L141） | `rgba(226,104,92,.16)` | `rgba(189,65,54,.18)` |
| 11 | `.card` 背景 linear-gradient（L151） | `rgba(255,255,255,.028)` | `rgba(255,255,255,.9)` |
| 12 | `.ring.outer` 的 `stroke`（L208） | `#2a323c` | `#c9d0da` |
| 13 | `.legend i` 的 `border`（L225） | `1px solid rgba(255,255,255,.22)` | `1px solid rgba(16,24,40,.18)` |
| 14 | `.tooltip` 的 `background`（L231） | `#0a0d11` | `#ffffff` |
| 15 | `.tooltip` 的 `box-shadow`（L236） | `0 8px 26px rgba(0,0,0,.55)` | `0 8px 26px rgba(16,24,40,.18)` |
| 16 | `thead th` 的 `background`（sticky 表头，L255） | `#141920` | `var(--bg-inset)` |
| 17 | `tbody tr:hover td` 的 `background`（L279） | `rgba(255,255,255,.03)` | `rgba(16,24,40,.035)` |
| 18 | `::-webkit-scrollbar-thumb` 的 `background`（L353） | `#232b34` | `#c3cbd6` |
| 19 | `::-webkit-scrollbar-thumb:hover` 的 `background`（L354） | `#2f3944` | `#a9b3c0` |

上表 19 项即 `app.css` 内全部硬编码颜色（已逐行盘点）；第 4、11 项故意保留白色低透明度作为浅色下的顶部高光，其余白色覆盖一律换成深色低透明度。

同时给 `.card` 补一条浅色卡片阴影（若既有 `.card` 规则已有 `box-shadow`，替换其值；否则在 `.card` 规则内追加）：

```css
  box-shadow: 0 1px 2px rgba(16, 24, 40, .06), 0 1px 3px rgba(16, 24, 40, .10);
```

- [ ] **Step 3: 改 app.js 的调色板与描边色**

① 把 `PALETTE`（L6）替换为浅底高对比 8 色，并在其后新增四个 SVG 用色常量：

```js
const PALETTE = ['#b0761a', '#26808f', '#3d8b50', '#bd4136', '#6b4fa1', '#9a7d12', '#3d6fa8', '#a44f7d'];
const GRID = '#e6e9ee';
const AXIS = '#8a94a2';
const LABEL = '#55606e';
const POINT_RING = '#ffffff';
```

② 把余额曲线数据点的描边（`drawBalance` 内，原为 `stroke="#11151b"`）改为：

```js
        markup += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="2.6" fill="${color}" stroke="${POINT_RING}" stroke-width="1" />`;
```

③ `GRID`/`AXIS`/`LABEL` 三个常量在本任务先声明、由 Task 8 的新图表函数使用；若 `app.css` 里 SVG 的 `.grid-line`/`.axis-text`/`.spoke`/`.ring`/`.axis-label` 等类已用 CSS 变量或 `currentColor` 描边填色，则**不需要**在 JS 里逐处传色——先在浏览器里确认这些类的浅色表现，只有确实读不到变量时才在新图表里用这三个常量。

- [ ] **Step 4: 核对无深色残留**

Run（在 `ecbench-app/src/main/resources/web/` 目录下跑两条）：

```bash
cd ecbench-app/src/main/resources/web
grep -nE "#0b0d10|#11151b|#0e1217|#1f262e|#171d24|#e9e6df|#8d97a2|#5d6771|#f0a63c|#57c6d6|#82c98f|#e2685c|#b48ce0|#e8d06a|#6fa8dc|#d98cb3|#0a0d11|#141920|#2a323c|#232b34|#2f3944|rgba\(0, ?0, ?0|rgba\(240, ?166, ?60|rgba\(87, ?198, ?214|rgba\(130, ?201, ?143|rgba\(226, ?104, ?92" app.css app.js
grep -nE "rgba\(255, ?255, ?255" app.css app.js
cd -
```

Expected：第 1 条 **无输出**（旧深色表面、旧调色板与旧语义色 rgba 全部清完）；第 2 条 **恰好 2 行命中**，即 Step 2 表格第 4 项 `.rail`（`rgba(255,255,255,.6)`）与第 11 项 `.card`（`rgba(255,255,255,.9)`）的顶部高光；多余命中必须清掉。

- [ ] **Step 5: 浏览器目视验收**

```bash
mvn -o -q -DskipTests package
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir log --port 8097 &
```

打开 `http://127.0.0.1:8097/`，逐项确认（用截图留证）：

1. 页面整体为浅色（背景 `#f5f6f8`、卡片白底、文字深色），无残留深色块
2. 左侧栏、KPI 卡、雷达图（网格线/轴线/轴标签/median 虚线）、排名表、余额曲线（网格线/坐标文字/数据点白描边/tooltip 白底）、指标矩阵、下钻两卡全部可读，对比度足够
3. 雷达与余额曲线的系列色在白底上清晰可辨（8 色不糊在一起）
4. 浏览器控制台 0 报错、0 失败请求

确认后 `kill %1` 停掉服务。

- [ ] **Step 6: 提交**

```bash
git add ecbench-app/src/main/resources/web/app.css ecbench-app/src/main/resources/web/app.js
git commit -m "style(web): switch dashboard to light theme without changing layout"
```

---

### Task 7: 全站中文（含 chatbox 界面中文化）

**Files:**
- Modify: `ecbench-app/src/main/resources/web/index.html`（整文件替换为最终中文版，含 Task 8 要用的新容器）
- Modify: `ecbench-app/src/main/resources/web/app.js`（固定字符串逐条替换 + 角色译名表 + `renderChatbox` 重写）
- Modify: `ecbench-app/src/main/resources/web/app.css`（chatbox 气泡与供应商标签的浅色样式）

**Interfaces:**
- Produces（Task 8 依赖的 DOM id 契约，本任务即在 index.html 中建好）：
  `#primary-cards`、`#dimensions`、`#crossings`、`#learning-note`；以及既有的 `#kpi-*`、`#radar*`、`#ranking`、`#balance-*`、`#metric-table`、`#metric-filter`、`#analysis`、`#chatbox`、`#drilldown`、`#drill-name`、`#session-list`、`#run-picker`、`#select-all`、`#clear`、`#status`、`#status-dot`
- Produces（JS 常量，Task 8 复用）：`ROLE_ZH`、`TERM_ZH`

- [ ] **Step 1: 整文件替换 index.html**

```html
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>E-CommerceBench · 运行遥测看板</title>
<link rel="stylesheet" href="/app.css" />
</head>
<body>

<aside class="rail">
  <div class="brand">
    <div class="brand-mark">EC</div>
    <div>
      <h1>E-CommerceBench</h1>
      <p class="brand-sub">运行遥测看板</p>
    </div>
  </div>

  <div class="rail-block">
    <h2 class="micro">会话</h2>
    <div id="session-list" class="session-list"><p class="hint">加载中…</p></div>
    <div class="rail-actions">
      <button id="select-all" type="button">全选</button>
      <button id="clear" type="button">清空</button>
    </div>
  </div>

  <div class="rail-block">
    <h2 class="micro">下钻</h2>
    <div id="run-picker" class="run-picker"><p class="hint">选择会话后出现</p></div>
  </div>

  <div class="rail-foot">
    <span class="dot" id="status-dot"></span>
    <span id="status">连接中…</span>
  </div>
</aside>

<main class="stage">

  <section class="kpis" id="kpis">
    <article class="kpi reveal"><span class="micro">最佳主评分（年末总资产倍数）</span><strong id="kpi-score">—</strong><em id="kpi-score-who"></em></article>
    <article class="kpi reveal"><span class="micro">已对比会话</span><strong id="kpi-sessions">—</strong><em id="kpi-runs"></em></article>
    <article class="kpi reveal"><span class="micro">有数据的画像轴</span><strong id="kpi-axes">—</strong><em>共 7 轴（主评分 + 六维度）</em></article>
    <article class="kpi reveal"><span class="micro">工具调用均值</span><strong id="kpi-calls">—</strong><em id="kpi-calls-note"></em></article>
  </section>

  <section class="grid">
    <article class="card span-12 reveal">
      <header class="card-head">
        <h2>主评分与六维度排名</h2>
        <span class="micro">论文 §3.7 · 主评分 = 年末总资产 ÷ ¥100,000</span>
      </header>
      <div id="primary-cards" class="primary-cards"><p class="hint">—</p></div>
      <p class="note">年末总资产 = 银行账户 + 平台钱包 + 未结算托管（余额 CSV 的 total_balance 权威列）。</p>
    </article>

    <article class="card span-12 reveal">
      <header class="card-head">
        <h2>六维度诊断</h2>
        <span class="micro">论文 §4.3.1–4.3.6 · 每卡顶部为该维度的排名指标</span>
      </header>
      <div id="dimensions" class="dimensions"><p class="hint">—</p></div>
    </article>

    <article class="card span-5 reveal">
      <header class="card-head">
        <h2>七轴画像</h2>
        <span class="micro">min–max 归一化 · 0–1 · 离中心越远越好</span>
      </header>
      <div class="chart-wrap"><svg id="radar" viewBox="0 0 420 380" role="img" aria-label="七轴归一化雷达图"></svg></div>
      <div id="radar-legend" class="legend"></div>
      <p id="radar-note" class="note"></p>
    </article>

    <article class="card span-7 reveal">
      <header class="card-head">
        <h2>排名交叉</h2>
        <span class="micro">论文附录 F.8 · 标红 = 与主评分排名不一致</span>
      </header>
      <div id="crossings" class="table-wrap"><p class="hint">—</p></div>
      <div id="ranking" class="table-wrap"><p class="hint">—</p></div>
    </article>

    <article class="card span-12 reveal">
      <header class="card-head">
        <h2>资产轨迹叠加</h2>
        <span class="micro">total_balance 权威列 = 银行 + 平台钱包 + 未结算托管</span>
      </header>
      <div class="chart-wrap wide"><svg id="balance-chart" viewBox="0 0 960 300" role="img" aria-label="资产轨迹叠加图"></svg></div>
      <div id="balance-legend" class="legend"></div>
      <p id="balance-note" class="note"></p>
    </article>

    <article class="card span-12 reveal">
      <header class="card-head">
        <h2>指标矩阵</h2>
        <input id="metric-filter" class="filter" type="search" placeholder="过滤指标或维度…" aria-label="过滤指标或维度" />
      </header>
      <div id="metric-table" class="table-wrap tall"><p class="hint">—</p></div>
    </article>
  </section>

  <section class="grid" id="drilldown" hidden>
    <article class="card span-6 reveal">
      <header class="card-head">
        <h2>analysis.json 原始面板</h2>
        <span class="micro" id="drill-name"></span>
      </header>
      <div id="analysis" class="panels"><p class="hint">点击左侧会话名下钻</p></div>
    </article>

    <article class="card span-6 reveal">
      <header class="card-head">
        <h2>chatbox 谈判记录</h2>
        <span class="micro">按供应商分组 · 正文保留模型原文</span>
      </header>
      <div id="chatbox" class="transcript"><p class="hint">点击左侧会话名下钻</p></div>
    </article>
  </section>

</main>

<script src="/app.js"></script>
</body>
</html>
```

- [ ] **Step 2: app.js 固定字符串逐条替换（现值 → 新值，全部为精确匹配）**

| 位置（函数） | 现值 | 新值 |
|---|---|---|
| `renderSessions` | `` `r${session.runs}${session.hasBalance ? ' ·bal' : ''}` `` | `` `${session.runs} 个 run${session.hasBalance ? ' · 有余额' : ''}` `` |
| `renderKpis` | `'无有效主分'` | `'无有效主评分'` |
| `renderKpis` | `` `${runs} run(s)` `` | `` `${runs} 个 run` `` |
| `renderKpis` | `` `${calls.length} session(s) 有数据` `` | `` `${calls.length} 个会话有数据` `` |
| `renderRadar` 图例 | `'<span><i style="background:transparent;border-style:dashed"></i>median</span>'` | `'<span><i style="background:transparent;border-style:dashed"></i>中位数</span>'` |
| `renderRanking` 表头 | `'<table><thead><tr><th>session</th><th>primary score</th></tr></thead><tbody>'` | `'<table><thead><tr><th>会话</th><th>主评分（年末总资产）</th></tr></thead><tbody>'` |
| `paintMetricRows` 表头 | `` `<table><thead><tr><th>metric</th>${head.join('')}</tr></thead><tbody>${body}</tbody></table>` `` | `` `<table><thead><tr><th>指标</th>${head.join('')}</tr></thead><tbody>${body}</tbody></table>` `` |
| `drill` 错误提示 | `` `analysis 读取失败：${esc(error.message)}` `` | `` `analysis.json 读取失败：${esc(error.message)}` `` |
| `drill` 错误提示 | `` `chatbox 读取失败：${esc(error.message)}` `` | `` `谈判记录读取失败：${esc(error.message)}` `` |
| `renderAnalysis` 空态 | `'<p class="hint">空 analysis</p>'` | `'<p class="hint">analysis.json 为空</p>'` |
| `flatten` 顶层占位 | 该函数内 `level ? '' : 'value'` 的 `'value'` 字面量 | 改为 `'值'` |
| `drawBalance` 尾注 | `` `${state.balance.size} 条曲线 · ${dates.length} 个交易日` `` | `` `${state.balance.size} 条曲线 · ${dates.length} 个交易日 · 纵轴为年末总资产` `` |
| `boot` 空态提示 | `'未发现会话。请用 --log-dir 指向含 run 产物的目录。'` | 保持不变（已是中文） |

`drill` 中的 `$('drill-name').textContent = name + ' · run_0';` 改为 `name + ' · run_0（下钻固定首个 run）'`，明示既有已知偏差。

- [ ] **Step 3: 在 app.js 顶部新增译名表（`PALETTE` 等常量之后）**

```js
/* 角色与术语译名（spec §5.3）：论文/产物原始名保留英文，中文注解紧随其后。 */
const ROLE_ZH = {
  assistant: '智能体',
  tool: '供应商回复 · 工具结果',
  user: '系统',
  system: '系统',
};

const FRAUD_ZH = {
  fake_urgency: '虚假紧迫',
  future_discount: '未来折扣',
  qty_bait: '数量诱饵',
  quality_downgrade: '次品交付',
  vip_fee: 'VIP 会员费',
};

const BAND_ZH = {
  waiting: '等待', shipping: '发货', listing: '上架', withdraw: '提现',
  bargaining: '议价', state_polling: '状态查询', memory: '记忆', remainder: '其余',
};

const LAYER_ZH = {
  exp_return_rate_natural: '自然退货',
  exp_return_rate_from_pricing: '定价过高',
  exp_return_rate_from_ship_speed: '配送过慢',
  exp_return_rate_from_defective_fraud: '欺诈次品',
};

/* analysis.json 七个面板的中文注解（键名保留英文原文，便于与产物对照）。 */
const PANEL_ZH = {
  reward: 'reward（奖励与终止）',
  profitability: 'profitability（盈利与偿付）',
  negotiation_quality: 'negotiation_quality（谈判质量）',
  fulfilment_quality: 'fulfilment_quality（履约质量）',
  return_management: 'return_management（退货管理）',
  fraud_identification: 'fraud_identification（欺诈识别）',
  supplier_engagement: 'supplier_engagement（供应商触达）',
  operational_efficiency: 'operational_efficiency（运营效率）',
};

const roleZh = (role) => ROLE_ZH[role] || role || '未知';
```

- [ ] **Step 3b: 让 analysis 下钻的分组名带中文注解**

把 `renderAnalysis` 里的 `<summary>${esc(panel)}</summary>` 改为：

```js
      <summary>${esc(PANEL_ZH[panel] || panel)}</summary>
```

- [ ] **Step 4: 重写 `renderChatbox`（角色中文化 + 供应商标签，正文保留原文）**

```js
function renderChatbox(payload) {
  const suppliers = payload.suppliers || [];
  if (!suppliers.length) {
    $('chatbox').innerHTML = '<p class="hint">该 run 没有 chatbox 谈判记录</p>';
    return;
  }
  $('chatbox').innerHTML = suppliers.map((supplier, index) => `
    <details class="supplier"${index === 0 ? ' open' : ''}>
      <summary><b>${esc(supplier.supplier)}</b>
        <span class="count">${supplier.messages.length} 条</span>
        ${rosterTag(supplier)}</summary>
      ${supplier.messages.map((m) => `
        <div class="msg ${esc(m.role)}">
          <span class="who">${esc(roleZh(m.role))}</span>
          <span class="body">${esc(collapse(m.content))}</span>
        </div>`).join('')}
    </details>`).join('');
}

/** 供应商标签：欺诈者标出骗局类型，诚信者标出人格；名册未命中则不显示标签。 */
function rosterTag(supplier) {
  if (supplier.supplierType === 'bad') {
    const fraud = FRAUD_ZH[supplier.fraudType] || supplier.fraudType || '未知骗局';
    return `<span class="tag bad">欺诈 · ${esc(fraud)}</span>`;
  }
  if (supplier.supplierType === 'good') {
    const who = supplier.personality ? ` · ${esc(supplier.personality)}` : '';
    return `<span class="tag good">诚信${who}</span>`;
  }
  return '';
}
```

- [ ] **Step 5: app.css 追加浅色样式（chatbox 气泡、标签、以及 Task 8 新面板的容器样式）**

在 `app.css` 末尾追加：

```css
/* ---------- chatbox：浅色气泡 + 角色色条 + 供应商标签 ---------- */

.msg {
  background: var(--bg-inset);
  border-left: 3px solid var(--ink-faint);
  border-radius: 3px;
  padding: 6px 8px;
  margin: 6px 0;
}
.msg.assistant { border-left-color: var(--cyan); }
.msg.tool { border-left-color: var(--amber); }
.msg.user, .msg.system { border-left-color: var(--ink-faint); }
.msg .who {
  display: block;
  font-size: 10px;
  letter-spacing: .08em;
  color: var(--ink-dim);
  margin-bottom: 3px;
}
.msg.assistant .who { color: var(--cyan); }
.msg.tool .who { color: var(--amber); }
.msg .body { color: var(--ink); white-space: pre-wrap; word-break: break-word; }

.supplier summary { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.supplier .count { color: var(--ink-faint); font-size: 11px; }
.tag {
  font-size: 10px;
  letter-spacing: .06em;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid currentColor;
}
.tag.bad { color: var(--red); background: rgba(189, 65, 54, .08); }
.tag.good { color: var(--green); background: rgba(61, 139, 80, .08); }

/* ---------- 主评分卡与六维度卡（Task 8 填充内容） ---------- */

.primary-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; }
.primary-card { background: var(--bg-inset); border: 1px solid var(--line-soft); border-radius: 4px; padding: 10px 12px; }
.primary-card .who { color: var(--ink-dim); font-size: 11px; }
.primary-card .value { font-family: var(--display); font-size: 22px; color: var(--ink); }
.primary-card .rank { color: var(--ink-faint); font-size: 11px; }

.dimensions { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
.dimension { border: 1px solid var(--line); border-radius: 4px; padding: 10px 12px; background: var(--bg-card); }
.dimension header { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
.dimension h3 { font-family: var(--display); font-size: 15px; margin: 0; color: var(--ink); }
.dimension .ref { color: var(--ink-faint); font-size: 10px; }
.dimension .headline { display: flex; align-items: baseline; gap: 8px; margin: 6px 0 8px; }
.dimension .headline b { font-family: var(--display); font-size: 20px; color: var(--ink); }
.dimension table { width: 100%; border-collapse: collapse; font-size: 12px; }
.dimension th, .dimension td { text-align: right; padding: 2px 4px; border-bottom: 1px solid var(--line-soft); }
.dimension th:first-child, .dimension td:first-child { text-align: left; color: var(--ink-dim); }
.dimension td.na { color: var(--ink-faint); }
.bars { margin: 8px 0 2px; }
.bar-row { display: grid; grid-template-columns: 96px 1fr 84px; align-items: center; gap: 6px; font-size: 11px; }
.bar-row .track { background: var(--bg-inset); border-radius: 2px; height: 8px; overflow: hidden; }
.bar-row .fill { height: 100%; background: var(--cyan); }
.bar-row .num { text-align: right; color: var(--ink-dim); font-variant-numeric: tabular-nums; }
.stacked { display: flex; height: 10px; border-radius: 2px; overflow: hidden; background: var(--bg-inset); margin: 6px 0; }
.stacked i { display: block; height: 100%; }
.legend-inline { display: flex; flex-wrap: wrap; gap: 8px; font-size: 10px; color: var(--ink-dim); }
.legend-inline span i { display: inline-block; width: 8px; height: 8px; margin-right: 4px; border-radius: 2px; }

/* ---------- rank crossings ---------- */

#crossings table { width: 100%; border-collapse: collapse; font-size: 12px; }
#crossings th, #crossings td { padding: 3px 6px; border-bottom: 1px solid var(--line-soft); text-align: center; }
#crossings th:first-child, #crossings td:first-child { text-align: left; }
#crossings td.cross { border-left: 3px solid var(--red); background: rgba(189, 65, 54, .07); }
#crossings td.na { color: var(--ink-faint); }

/* ---------- 指标矩阵的维度分组 ---------- */

#metric-table tr.group-head td {
  background: var(--bg-inset);
  color: var(--ink-dim);
  font-size: 11px;
  letter-spacing: .08em;
  cursor: pointer;
}
```

- [ ] **Step 6: 浏览器验收中文与 chatbox 标注**

```bash
mvn -o -q -DskipTests package
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir log --port 8096 &
```

打开 `http://127.0.0.1:8096/`，确认：页面标题、侧栏、KPI、卡片标题、表头、按钮、提示全为中文；术语保留英文（如 `total_balance`、`CSE+`）；下钻任一含 chatbox 的会话（`20261010_173016_qwen3.7-plus`），供应商分组标题出现「诚信 · Friendly」或「欺诈 · VIP 会员费」这类标签，消息角色显示为「智能体」「供应商回复 · 工具结果」，正文仍是英文原文。控制台 0 报错。确认后 `kill %1`。

（`#primary-cards`、`#dimensions`、`#crossings` 三处此时仍显示占位 `—`，由 Task 8 填充，属预期。）

- [ ] **Step 7: 提交**

```bash
git add ecbench-app/src/main/resources/web/index.html \
        ecbench-app/src/main/resources/web/app.js \
        ecbench-app/src/main/resources/web/app.css
git commit -m "feat(web): switch dashboard copy to Chinese and annotate chatbox roles"
```

---

### Task 8: 主评分卡、六维度卡（含三类诊断图）、排名交叉与矩阵分组

**Files:**
- Modify: `ecbench-app/src/main/resources/web/app.js`（新增 6 个渲染函数 + 2 个常量表；改 `loadCompare`、`renderKpis`、`paintMetricRows`、`renderRadar` 的轴标签）

**Interfaces:**
- Consumes: Task 4 的 `/api/compare` 四段（`primaryScore`/`dimensions`/`rankCrossings`/`learning`）、Task 7 的 DOM id 与 `BAND_ZH`/`FRAUD_ZH`/`LAYER_ZH` 译名表
- Produces: `renderPrimary(data)`、`renderDimensions(data)`、`renderCrossings(data)`、`barsBlock(entries)`、`stackedBlock(entries, colors)`、`renderLearningCard(learning, sessions)`、`dualCaliberNote(name, value, sessions)`、`trackerHalfLift(sessions, name)`、`METRIC_GROUPS`、`AXIS_ZH`

- [ ] **Step 1: 新增常量表（放在 Task 7 的译名表之后）**

```js
/* 指标矩阵的维度分组（spec §5.5，29 个 label 的归属固定，不得临场判断）。 */
const METRIC_GROUPS = [
  ['谈判质量', ['SE+', 'CSE+', '%Oracle', 'AGR+', 'avg_rounds', 'money_saved']],
  ['欺诈规避', ['bad_order_share', 'spend_on_bad', 'spend_bad_share', 'vip_fee_paid']],
  ['现金流与偿付', ['final_balance', 'initial_balance', 'peak_total_assets', 'peak_drawdown',
    'bankrupt', 'final_day', 'stores / reopens']],
  ['运营效率', ['tool_calls', 'profit_per_tool_call', 'context_evictions', 'memory_calls']],
  ['运营执行', ['on_time_ship', 'return_rate', 'controllable_return_rate']],
  ['长周期学习', ['se_half_lift', 'agr_half_lift', 'fraud_avoid_lift', 'time_to_zero_bad',
    'rounds_improvement']],
];

/* 雷达轴标签的中文注解（轴序与后端 ProfileAxis 一致）。 */
const AXIS_ZH = [
  '主评分', '谈判质量', '欺诈规避', '现金流与偿付', '运营效率', '运营执行', '学习（AnchorRatio）',
];
```

- [ ] **Step 2: 新增主评分卡渲染**

```js
function renderPrimary(data) {
  const box = $('primary-cards');
  const rows = (data.primaryScore && data.primaryScore.rows) || [];
  if (!rows.length) {
    box.innerHTML = '<p class="hint">无主评分数据（analysis.json 缺 reward.final_score）</p>';
    return;
  }
  box.innerHTML = rows.map((row) => `
    <div class="primary-card">
      <div class="who">${esc(shortName(row.name))}</div>
      <div class="value">${row.value === null ? '不适用' : row.value.toFixed(2) + ' ×'}</div>
      <div class="rank">排名第 ${row.rank === null ? '—' : row.rank} · 年末总资产 ${fmtMoney(row.finalBalance)}</div>
      <div class="rank">破产 run ${row.bankruptRuns} 个 · 终止原因 ${esc(row.termination || '未知')}</div>
    </div>`).join('');
}
```

- [ ] **Step 3: 新增六维度卡渲染（含三类诊断图）**

```js
function renderDimensions(data) {
  const box = $('dimensions');
  const axes = (data.dimensions || []);
  if (!axes.length) {
    box.innerHTML = '<p class="hint">无维度数据</p>';
    return;
  }
  box.innerHTML = axes.map((axis) => `
    <article class="dimension">
      <header>
        <h3>${esc(axis.labelZh)}</h3>
        <span class="ref">${esc(axis.paperRef)}</span>
      </header>
      ${dimensionHeadline(axis, data)}
      ${dimensionTable(axis)}
      ${dimensionBlocks(axis, data)}
      ${axis.key === 'learning' ? renderLearningCard(data.learning, data.sessions) : ''}
    </article>`).join('');
}

/** 卡片顶部：排名指标名（英文 + 中文注解）、方向、各会话的值与 rank。 */
function dimensionHeadline(axis, data) {
  const metric = axis.rankMetric || {};
  const direction = metric.direction === 'lower' ? '越低越好' : '越高越好';
  const rows = (axis.rows || []).map((row) => `
    <tr>
      <td>${esc(shortName(row.name))}</td>
      <td class="${row.value === null ? 'na' : ''}">${row.value === null ? '不适用' : fmtNum(row.value)}</td>
      <td class="${row.rank === null ? 'na' : ''}">${row.rank === null ? '—' : '#' + row.rank}</td>
    </tr>`).join('');
  return `
    <div class="headline">
      <b>${esc(metric.label || '')}</b>
      <span class="ref">${esc(metric.labelZh || '')} · ${direction}${metric.unit ? ' · ' + esc(metric.unit) : ''}</span>
    </div>
    <table><thead><tr><th>会话</th><th>值</th><th>维度排名</th></tr></thead><tbody>${rows}</tbody></table>`;
}

/** 诊断指标小表：行 = 诊断指标（英文 + 中文注解），列 = 会话。 */
function dimensionTable(axis) {
  const diagnostics = axis.diagnostics || [];
  if (!diagnostics.length) return '';
  const names = (axis.rows || []).map((row) => row.name);
  const head = names.map((name) => `<th>${esc(shortName(name))}</th>`).join('');
  const body = diagnostics.map((item) => `
    <tr>
      <td title="${esc(item.label)}">${esc(item.label)}<span class="ref"> ${esc(item.labelZh)}</span></td>
      ${names.map((name) => {
        const value = item.values ? item.values[name] : null;
        return `<td class="${value === null || value === undefined ? 'na' : ''}">${fmtNum(value)}</td>`;
      }).join('')}
    </tr>`).join('');
  return `<table><thead><tr><th>诊断指标</th>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

/** 三类嵌套诊断块：八类活动带、五类骗局支出、退货四层 + 发货速度构成。 */
function dimensionBlocks(axis, data) {
  const blocks = axis.blocks || {};
  if (axis.key === 'efficiency' && blocks.callsByBand) {
    const entries = Object.entries(blocks.callsByBand).map(([band, value]) => [
      `${band}（${BAND_ZH[band] || band}）`, value.count, value.share,
    ]);
    return barsBlock(entries);
  }
  if (axis.key === 'fraud' && blocks.spendByFraudType) {
    const entries = Object.entries(blocks.spendByFraudType);
    const colors = ['#bd4136', '#b0761a', '#6b4fa1', '#26808f', '#a44f7d'];
    const stacked = stackedBlock(
      entries.map(([type, value]) => [`${type}（${FRAUD_ZH[type] || type}）`, value.spend]), colors);
    const detail = entries.map(([type, value]) => `
      <div class="bar-row">
        <span>${esc(type)}</span>
        <span class="num">${fmtNum(value.orders)} 单 / ${fmtNum(value.units)} 件</span>
        <span class="num">${fmtMoney(value.spend)}</span>
      </div>`).join('');
    return stacked + detail;
  }
  if (axis.key === 'execution') {
    const layers = blocks.returnLayers || {};
    const entries = Object.entries(layers).map(([key, value]) => [LAYER_ZH[key] || key, value]);
    const colors = ['#8a94a2', '#b0761a', '#26808f', '#bd4136'];
    const speed = blocks.shipSpeed
      ? `<div class="legend-inline">${Object.entries(blocks.shipSpeed)
          .map(([key, value]) => `<span>${esc(key)}：${fmtNum(value)}</span>`).join('')}</div>`
      : '';
    return stackedBlock(entries, colors) + speed;
  }
  return '';
}

/** 横向条：entries = [[标签, 计数, 占比]]，条长按占比归一化。 */
function barsBlock(entries) {
  const max = Math.max(1, ...entries.map((entry) => Number(entry[2]) || 0));
  return `<div class="bars">${entries.map(([label, count, share]) => `
    <div class="bar-row">
      <span>${esc(label)}</span>
      <span class="track"><i class="fill" style="width:${(((Number(share) || 0) / max) * 100).toFixed(1)}%"></i></span>
      <span class="num">${fmtNum(count)} 次 · ${fmtNum(share)}%</span>
    </div>`).join('')}</div>`;
}

/** 堆叠条：entries = [[标签, 数值]]，按总量占比分段；总量为 0 时显示空态。 */
function stackedBlock(entries, colors) {
  const total = entries.reduce((sum, entry) => sum + (Number(entry[1]) || 0), 0);
  if (!total) return '<p class="hint">无样本（全部为 0）</p>';
  const segments = entries.map((entry, index) => {
    const value = Number(entry[1]) || 0;
    const width = ((value / total) * 100).toFixed(2);
    return `<i style="width:${width}%;background:${colors[index % colors.length]}" title="${esc(entry[0])}"></i>`;
  }).join('');
  const legend = entries.map((entry, index) => `
    <span><i style="background:${colors[index % colors.length]}"></i>${esc(entry[0])} ${fmtNum(entry[1])}</span>`).join('');
  return `<div class="stacked">${segments}</div><div class="legend-inline">${legend}</div>`;
}

/** 学习维度卡：论文 §E.6 的锚定与半年比较；无产物时给出中文空态与条款出处。 */
function renderLearningCard(learning, sessions) {
  const entries = learning ? Object.entries(learning) : [];
  if (!entries.length) return '<p class="hint">无学习维度数据</p>';
  return entries.map(([name, value]) => {
    if (!value || value.episodes === 0) {
      return `<p class="hint">${esc(shortName(name))}：无重复采购记录，AnchorRatio 不适用（§E.6 过滤规则 4）；
        若该会话产物早于逐谈判记录落盘，需重跑基准才能计算。</p>`;
    }
    const rows = [
      ['AnchorRatio', '锚定比（越低越好，1.0 = 与随机排列无异）', value.anchorRatio],
      ['AnchorRegret', '锚定遗憾', value.anchorRegret],
      ['NewLow', '创新低率', value.newLow],
      ['z_anchor', '锚定 z 分数（Stouffer 合并）', value.zAnchor],
      ['h_SE', '剩余半期提升', value.seHalfLift],
      ['b_SE', '逐日盈余趋势', value.seTrend],
      ['h_fraud', '欺诈半期提升', value.fraudHalfLift],
    ];
    return `<table><thead><tr><th>学习指标</th><th>值</th></tr></thead><tbody>
      ${rows.map(([label, note, raw]) => `
        <tr><td title="${esc(note)}">${esc(label)}<span class="ref"> ${esc(note)}</span></td>
        <td class="${raw === null ? 'na' : ''}">${fmtNum(raw)}</td></tr>`).join('')}
      <tr><td>episodes / repeatPairs / reorders<span class="ref"> 计入回合数 / 重复采购对 / 再订购次数</span></td>
        <td>${value.episodes} / ${value.repeatPairs} / ${value.reorders}</td></tr>
    </tbody></table>${dualCaliberNote(name, value, sessions)}`;
  }).join('');
}

/**
 * 双口径提示（spec §5.5）：矩阵里的 se_half_lift 来自 analysis.json 的追踪器口径，本卡是 §E.6 重算值；
 * 两者都有值且差 &gt; 1e-6 时明示差异，避免误读。
 */
function dualCaliberNote(name, value, sessions) {
  const tracked = trackerHalfLift(sessions, name);
  if (tracked === null || value.seHalfLift === null || value.seHalfLift === undefined) return '';
  if (Math.abs(tracked - value.seHalfLift) <= 1e-6) return '';
  return '<p class="note">与追踪器 learning_speed.se_half_lift 存在差异（排序口径），以本卡 §E.6 重算值为准。</p>';
}

/** 从指标矩阵取追踪器口径的 se_half_lift（analysis.json 的 negotiation_quality.learning_speed）。 */
function trackerHalfLift(sessions, name) {
  const session = (sessions || []).find((item) => item.name === name);
  if (!session) return null;
  const stat = (session.metrics || []).find((metric) => metric.label === 'se_half_lift');
  return stat && typeof stat.mean === 'number' ? stat.mean : null;
}
```

- [ ] **Step 4: 新增排名交叉表渲染**

```js
/** rank crossings（附录 F.8）：与主评分排名不一致的格子标红。 */
function renderCrossings(data) {
  const rows = data.rankCrossings || [];
  const box = $('crossings');
  if (!rows.length) {
    box.innerHTML = '<p class="hint">无排名数据</p>';
    return;
  }
  const axes = (data.dimensions || []).map((axis) => [axis.key, axis.labelZh]);
  const head = axes.map(([, labelZh]) => `<th>${esc(labelZh)}</th>`).join('');
  const body = rows.map((row) => `
    <tr>
      <td>${esc(shortName(row.name))}</td>
      <td>${row.primary === null || row.primary === undefined ? '—' : '#' + row.primary}</td>
      ${axes.map(([key]) => {
        const rank = row.ranks ? row.ranks[key] : null;
        const crosses = rank !== null && rank !== undefined && row.primary !== null && rank !== row.primary;
        return `<td class="${rank === null || rank === undefined ? 'na' : ''}${crosses ? ' cross' : ''}">
          ${rank === null || rank === undefined ? '—' : '#' + rank}</td>`;
      }).join('')}
    </tr>`).join('');
  box.innerHTML = `<table><thead><tr><th>会话</th><th>主评分</th>${head}</tr></thead><tbody>${body}</tbody></table>`;
}
```

- [ ] **Step 5: 指标矩阵改为按维度分组（重写 `paintMetricRows`）**

```js
function paintMetricRows() {
  const head = state.sessions.filter((s) => state.selected.has(s.name))
    .map((s) => `<th>${esc(shortName(s.name))}</th>`);
  const byLabel = new Map(metricRows.map((row) => [row.label, row]));
  const rowHtml = (row) => `
    <tr data-label="${esc(row.label.toLowerCase())}">
      <td>${esc(row.label)}</td>
      ${row.cells.map((c) => `<td class="${c.na ? 'na' : ''}">${esc(c.text)}</td>`).join('')}
    </tr>`;
  const grouped = new Set();
  let body = '';
  METRIC_GROUPS.forEach(([title, labels], index) => {
    const rows = labels.map((label) => byLabel.get(label)).filter(Boolean);
    if (!rows.length) return;
    rows.forEach((row) => grouped.add(row.label));
    body += `<tr class="group-head" data-group="${index}" data-label="${esc(title.toLowerCase())}">
      <td colspan="${head.length + 1}">${esc(title)}（${rows.length}）</td></tr>`;
    body += rows.map(rowHtml).join('');
  });
  // 归组之外的 label（例如后端新增指标）兜底到「其余」组，避免任何一行被静默丢弃
  const rest = metricRows.filter((row) => !grouped.has(row.label));
  if (rest.length) {
    body += `<tr class="group-head" data-label="其余"><td colspan="${head.length + 1}">其余（${rest.length}）</td></tr>`;
    body += rest.map(rowHtml).join('');
  }
  $('metric-table').innerHTML =
    `<table><thead><tr><th>指标</th>${head.join('')}</tr></thead><tbody>${body}</tbody></table>`;
  filterMetricRows();
}
```

同时把 `filterMetricRows` 改为「命中行或其所属分组标题都保留」：

```js
function filterMetricRows() {
  const needle = ($('metric-filter').value || '').trim().toLowerCase();
  const rows = [...document.querySelectorAll('#metric-table tbody tr')];
  if (!needle) {
    rows.forEach((tr) => { tr.hidden = false; });
    return;
  }
  let keepGroup = false;
  rows.forEach((tr) => {
    const isGroup = tr.classList.contains('group-head');
    if (isGroup) {
      keepGroup = tr.dataset.label.includes(needle);
      tr.hidden = !keepGroup;
      return;
    }
    const hit = tr.dataset.label.includes(needle);
    tr.hidden = !(hit || keepGroup);
  });
}
```

`group-head` 的 `colspan` 为「1（指标名列）+ 会话数」，与表头列数一致；归组之外的 label 兜底到「其余」组，保证后端将来新增指标时不会有任何一行被静默丢弃。

- [ ] **Step 6: 雷达轴标签中文化 + KPI 改主评分口径**

① `renderRadar` 里轴标签取自 `radar.axes`（后端英文 label），改为优先用中文注解：

```js
  axes.forEach((label, idx) => {
    const [x, y] = pointAt(idx, 1.2);
    const anchor = Math.abs(x - cx) < 12 ? 'middle' : (x > cx ? 'start' : 'end');
    const cls = liveAxes.has(idx) ? 'axis-label' : 'axis-label muted';
    const text = AXIS_ZH[idx] || label;
    markup += `<text class="${cls}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" text-anchor="${anchor}">`
      + `<title>${esc(label)}</title>${esc(text)}</text>`;
  });
```

（`<title>` 保留后端英文原 label，鼠标悬停可对照论文口径；同时删除原先按 22 字符截断的逻辑，中文标签更短不需要截断。）

② `renderKpis` 的主评分卡改为读 `primaryScore`（资产倍数）而不是 `ranking[0].score`：

```js
  const rows = (data.primaryScore && data.primaryScore.rows) || [];
  const best = rows.find((row) => row.rank === 1) || null;
  $('kpi-score').textContent = best && best.value !== null ? best.value.toFixed(2) + ' ×' : '不适用';
  $('kpi-score-who').textContent = best ? shortName(best.name) : '无有效主评分';
```

（替换既有 `const top = data.ranking.find(...)` 起的两行；其余 KPI 逻辑不变。）

- [ ] **Step 7: 接进 `loadCompare`**

把 `loadCompare` 的成功分支改为：

```js
    const data = await api('/api/compare?' + query);
    setStatus(`已对比 ${data.sessions.length} 个会话`, 'live');
    renderKpis(data);
    renderPrimary(data);
    renderDimensions(data);
    renderCrossings(data);
    renderRadar(data.radar, data.sessions);
    renderRanking(data.ranking);
    renderMetricTable(data.sessions);
    renderRunPicker(data.sessions);
    await loadBalance(chosen);
```

并把「未选择会话」分支的清空列表补上新容器：

```js
    ['ranking', 'metric-table', 'radar-legend', 'primary-cards', 'dimensions', 'crossings']
      .forEach((id) => { $(id).innerHTML = ''; });
```

- [ ] **Step 8: 浏览器验收（真实产物 + 合成产物）**

```bash
mvn -o -q -DskipTests package
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir log --port 8095 &
```

1. 打开 `http://127.0.0.1:8095/`，勾选全部 4 个会话：主评分卡出现 4 张（倍数 + 排名）；六维度卡按论文顺序出现，谈判/欺诈/偿付/效率/执行五卡有值，学习卡显示中文空态与 §E.6 条款；效率卡有八类活动带横条（含中文带名）；欺诈卡有五类骗局堆叠条 + 单量/件数/金额；执行卡有退货四层堆叠条 + 发货速度构成（现有产物无销售 → 显示「无样本（全部为 0）」，属预期）；排名交叉表出现，与主评分不一致的格子标红。
2. 指标矩阵按六维度分组折叠，分组标题中文、行 label 保留英文；过滤框输入「欺诈」只留该组，输入 `spend` 命中具体行。
3. 雷达轴标签为中文，悬停显示英文原 label。
4. 控制台 0 报错、0 失败请求（`/api/compare` 一次返回全部四段）。
5. 截图留证：整页、六维度卡区、排名交叉表、指标矩阵分组、chatbox 标注。

确认后 `kill %1`。

- [ ] **Step 9: 提交**

```bash
git add ecbench-app/src/main/resources/web/app.js
git commit -m "feat(web): render paper dimension cards, rank crossings and diagnostic charts"
```

---

### Task 9: 文档、门禁与最终验收

**Files:**
- Modify: `README.md`（Web 看板小节）

**Interfaces:** 无

- [ ] **Step 1: 更新 README 的 serve 小节**

把 `### 本地 Web 看板（serve）` 小节的命令行与两段说明替换为：

```markdown
```bash
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080 --host 127.0.0.1
# 打开 http://127.0.0.1:8080/ ：会话勾选、主评分与六维度排名、六维度诊断卡（八类活动带 / 五类骗局支出 / 退货四层）、
# 排名交叉、七轴雷达、资产轨迹叠加、按维度分组的指标矩阵、单 run 下钻（analysis.json + chatbox 谈判记录，含欺诈标注）
# Ctrl-C 停止
```

选项：`--log-dir`（会话根目录，默认 `log`）、`--port`（默认 `8080`，`0` 为随机端口）、`--host`（默认 `127.0.0.1`）、
`--catalog`（供应商名册 CSV，默认取资源根 `data/suppliers.csv`；用于在 chatbox 谈判记录上标注供应商类型与欺诈类型，
名册缺失时自动降级为无标注，服务照常启动）。

只读接口：`/api/sessions`、`/api/compare?sessions=<name>`（缺省对比全部会话；返回 `sessions`/`radar`/`ranking`
以及论文 §4.3 的 `primaryScore`/`dimensions`/`rankCrossings`/`learning` 四段）、
`/api/sessions/<name>/{analysis|balance|chatbox}?run=<i>`。
数值口径完全复用 `evaluate` 子命令的同一套实现（`RunComparator` / `CrossModelProfiler` / `RankingTable`），
维度视图口径见 `DimensionAxis`（论文 §3.7）；前端为零依赖单页应用，图表以纯 SVG 渲染，不引用任何外部 CDN 资源。
```

- [ ] **Step 2: 全量门禁**

```bash
mvn -o clean test
mvn -o -q -pl ecbench-evaluation,ecbench-app checkstyle:check
mvn -o -q -DskipTests package
```

Expected: 三条命令全部 BUILD SUCCESS；两处 checkstyle 均输出 `You have 0 Checkstyle violations.`；`ecbench-app/target/ecbench-app.jar` 重新生成。

不要运行 `mvn spotless:apply`；也不要指望 `mvn clean verify` 全绿（spotless 绑在 verify 阶段且基线为红，见阶段 A 计划 Task 11 的实测记录）。

- [ ] **Step 3: 旧产物零回归验证**

```bash
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir log --port 8094 &
sleep 3
curl -s "http://127.0.0.1:8094/api/compare" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('legacy sections:', sorted(k for k in d if k in ('sessions','radar','ranking')))
print('new sections   :', sorted(k for k in d if k in ('primaryScore','dimensions','rankCrossings','learning')))
print('sessions       :', len(d['sessions']), 'radar axes:', len(d['radar']['axes']), 'ranking:', len(d['ranking']))
print('dimensions     :', [a['key'] for a in d['dimensions']])
print('learning null  :', all(v['anchorRatio'] is None for v in d['learning'].values()))
"
curl -s -o /dev/null -w "traversal=%{http_code}\n" "http://127.0.0.1:8094/api/sessions/..%2F..%2Fetc/analysis"
curl -s -o /dev/null -w "unknown=%{http_code}\n" "http://127.0.0.1:8094/nope"
curl -s -o /dev/null -w "post=%{http_code}\n" -X POST "http://127.0.0.1:8094/api/sessions"
kill %1
```

Expected: `legacy sections` 与 `new sections` 各 3/4 项齐备；`sessions 4`、`radar axes 7`、`ranking ≥1`；`dimensions` 为 `['negotiation','fraud','solvency','efficiency','execution','learning']`；`learning null True`（现有 4 个会话都没有逐谈判记录）；`traversal=400`、`unknown=404`、`post=405`。

- [ ] **Step 4: 浏览器最终验收（对照 spec §7 的 DoD 逐条打勾）**

用 Task 8 Step 8 的清单再走一遍，另加：
1. 浅色主题无深色残留（Task 6 Step 4 的 grep 已核；此处目视复核 tooltip、滚动条、hover 态）
2. 全站中文，术语保留英文；无未翻译的英文界面文案（`median`、`session`、`metric`、`primary score` 等应已中文化）
3. chatbox 角色为中文、正文保留英文原文、供应商标签可见
4. 截图留证并在收尾报告中给出路径

- [ ] **Step 5: 提交**

```bash
git add README.md
git commit -m "docs: describe paper dimension views and serve --catalog in README"
```

- [ ] **Step 6: 收尾报告（必须包含的证据）**

- `git log --oneline` 显示 Task 1–9 各自的小步提交
- `mvn -o clean test` 全绿的输出摘要（测试数 / 0 failures）
- 两处 `checkstyle:check` 的 `0 Checkstyle violations`
- Step 3 的 curl 校验输出（旧产物零回归 + 安全码 400/404/405）
- Task 6 Step 4 的 grep 无输出（深色残留为 0）
- 截图路径清单
- 如实说明：现有 `log/` 四个会话为 2 天短 run，学习维度与运营执行维度为空/全 0 属预期；要看到真实数据需按 spec §8 重跑 365 天 episode（需用户提供 LLM API key）

---

## 阶段 B 完成判据

1. `/api/compare` 返回论文四段新契约，既有三段逐字段不变（`CompareServiceTest` + Task 9 Step 3 双重锁定）。
2. 六维度卡按论文 §4.3 顺序渲染，每卡顶部为该维度排名指标与 rank，内含三类诊断图（八类活动带 / 五类骗局支出 / 退货四层）。
3. 排名交叉表标出与主评分排名不一致的维度（附录 F.8）。
4. 主评分以「年末总资产倍数」呈现（`reward.final_score`），KPI 与主评分卡一致。
5. chatbox 每个供应商带 `supplierType`/`personality`/`fraudType` 标注；`--catalog` 不可用时降级为无标注且服务照常启动。
6. 整站浅色主题、全站中文（术语保留英文）、深色残留 grep 命中数为 0、控制台 0 报错 0 失败请求。
7. 安全性不回归：400 / 404 / 405 三类状态码与改动前一致。
8. README 的 serve 小节反映新面板、`--catalog` 与维度口径。
