# 阶段 A：论文 §E.6 学习维度指标（逐谈判记录落盘 + 评估层重算）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每次 run 落盘逐谈判原始记录，并在评估层按论文 §E.6 精确重算 `AnchorRegret`/`NewLow`/`AnchorRatio`/`z_anchor` 与三个半年比较，把雷达学习轴从 `se_half_lift` 代理切到论文口径 `AnchorRatio`。

**Architecture:** `ecbench-app/log` 新增一个 JSONL 写入器，由 `CompositeRunObserver` 在 run 收尾时把 `NegotiationTracker.completed()` 的记录逐行落盘为 `metrics/run_{i}_negotiation_records.jsonl`（纯 additive 产物）。`ecbench-evaluation` 新增读取器 + 纯函数计算器 + 会话级聚合服务，结果以可空 `SessionLearning` 挂在 `ComparisonReport.SessionComparison` 上，`ProfileAxis.LEARNING` 改为读它。`ecbench-opponent` 与 `MetricsJsonWriter` 零改动，既有 `analysis.json` / `negotiation_metrics.json` 字段与取值逐字不变。

**Tech Stack:** Java 17、Maven 多模块、Jackson `ObjectMapper`、JUnit 5 + AssertJ + `@TempDir`、`java.util.Random`（固定种子置换零分布）。无新增依赖。

**Spec:** `docs/superpowers/specs/2026-10-12-paper-capability-dashboard-design.md`（§3.2/§3.3 公式、§4 契约、§4.4 测试计划、§6 门禁）

## Global Constraints

- 语言级别 Java 17；**不新增任何 Maven 依赖**。
- 缩进：`ecbench-evaluation` 与 `ecbench-app` 均为 **4 空格**（镜像同模块既有文件；`ecbench-domain` 是 2 空格，本阶段不碰）。
- **禁止运行 `mvn spotless:apply`**（会按默认 GOOGLE 2 空格重排整模块，产生巨量无关 diff；Spotless 门禁在本特性开工前即为红，保持基线不恶化即可）。
- Checkstyle：行宽 ≤ 120、禁 star import、单方法 ≤ 150 行；**每个新增类必须有中文 Javadoc**。
- 单测命令统一带 `-am` 与 `-Dsurefire.failIfNoSpecifiedTests=false`，例如：
  `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
- 提交：在 `main` 上按任务小步提交（用户已授权）；每个 Task 末尾的 commit 步骤都要执行。
- 断言浮点一律用 `isCloseTo(expected, within(1e-9))`，不用 `isEqualTo`（π 位置值经除法后不是精确二进制小数）。
- 计算口径以 spec §3.2/§3.3 为唯一依据：π **截断**到 [0,1]，`SE_z` **不截断**；置换零分布的标准差是**总体**标准差（除以 B），与档案其他处的样本标准差刻意不同。
- `ecbench-opponent`、`ecbench-domain`、`ecbench-simulation`、`ecbench-agent`、`ecbench-llm`、`MetricsJsonWriter.java`、`RunComparator.METRIC_DEFS` 本阶段**一律不改**。

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `ecbench-app/.../app/log/RunDirectory.java` | 产物路径命名 | 改：+1 方法 |
| `ecbench-evaluation/.../evaluation/io/RunLayoutResolver.java` | organized/legacy 布局探测 | 改：`RunLayout` +1 方法 |
| `ecbench-evaluation/.../evaluation/model/NegotiationRecordRow.java` | jsonl 一行的不可变载体 | 新 |
| `ecbench-evaluation/.../evaluation/io/NegotiationRecordJsonlReader.java` | 读 jsonl，坏行跳过 | 新 |
| `ecbench-app/.../app/log/NegotiationRecordJsonlWriter.java` | 把 `NegotiationRecord` 写为 jsonl | 新 |
| `ecbench-app/.../app/log/CompositeRunObserver.java` | 事件分派到各写入器 | 改：+1 构造参数、+1 写入器 |
| `ecbench-app/.../app/run/RunComponentFactory.java` | 组件装配 | 改：传 `tracker::completed` |
| `ecbench-evaluation/.../evaluation/model/LearningMetrics.java` | 单 run（episode）学习指标 | 新 |
| `ecbench-evaluation/.../evaluation/model/SessionLearning.java` | 会话级聚合学习指标 | 新 |
| `ecbench-evaluation/.../evaluation/LearningMetricsCalculator.java` | §E.6 纯函数计算器 | 新 |
| `ecbench-evaluation/.../evaluation/LearningMetricsService.java` | 按会话读全部 run 并聚合 | 新 |
| `ecbench-evaluation/.../evaluation/model/ComparisonReport.java` | 比较报告 | 改：`SessionComparison` +可空 `learning` |
| `ecbench-evaluation/.../evaluation/RunComparator.java` | 多会话比较 | 改：填充 `learning` |
| `ecbench-evaluation/.../evaluation/ProfileAxis.java` | 七轴定义 | 改：`LEARNING` 口径与方向 |
| `README.md` | 使用说明 | 改：新产物 + 口径变更 |

测试文件：`RunDirectoryTest`（新）、`RunLayoutResolverTest`（新）、`NegotiationRecordJsonlReaderTest`（新）、`LearningMetricsCalculatorTest`（新）、`LearningMetricsServiceTest`（新）、`NegotiationRecordJsonlWriterTest`（新）、`CompositeRunObserverToolCallCountTest`（改构造调用）、`CompatibleRunLoggingTest`（改构造调用 + 新增 jsonl 断言）、`RunComparatorTest`（改）、`ProfileAxisTest`（改）。

---

### Task 1: 产物路径契约

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/log/RunDirectory.java`（在 `analysisJson` 之后追加）
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/RunLayoutResolver.java`（`RunLayout` record 内，`negotiationMetricsJson` 之后追加）
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/log/RunDirectoryTest.java`（新建）
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/RunLayoutResolverTest.java`（新建）

**Interfaces:**
- Produces:
  - `RunDirectory.negotiationRecordsJsonl(int runIndex) → Path`，值为 `{sessionDir}/metrics/run_{i}_negotiation_records.jsonl`
  - `RunLayoutResolver.RunLayout.negotiationRecordsJsonl(int runIndex) → Path`，值为 `{metricsDir}/run_{i}_negotiation_records.jsonl`（legacy 平铺时 `metricsDir == sessionDir`）

- [ ] **Step 1: 写失败测试（app 侧路径）**

新建 `ecbench-app/src/test/java/io/github/ecommercebench/app/log/RunDirectoryTest.java`：

```java
package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RunDirectory 路径契约测试：新增的逐谈判记录产物必须落在 metrics/ 下并沿用 run_{idx}_ 前缀。 */
class RunDirectoryTest {

    @Test
    void negotiationRecordsJsonlLivesUnderMetricsDir(@TempDir Path logDir) {
        RunDirectory directory = RunDirectory.create(logDir, "20260101_080000", "fake/model");

        assertThat(directory.negotiationRecordsJsonl(0))
                .isEqualTo(directory.metricsDir().resolve("run_0_negotiation_records.jsonl"));
        assertThat(directory.negotiationRecordsJsonl(3).getFileName().toString())
                .isEqualTo("run_3_negotiation_records.jsonl");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-app -am test -Dtest=RunDirectoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 方法 negotiationRecordsJsonl(int)`

- [ ] **Step 3: 实现 app 侧路径方法**

在 `RunDirectory.java` 的 `analysisJson(int)` 方法之后插入：

```java
    /** 逐谈判原始记录 JSONL（论文 §E.6 学习维度指标的重算输入，Java 侧附加产物）。 */
    public Path negotiationRecordsJsonl(int runIndex) {
        return metricsDir().resolve("run_" + runIndex + "_negotiation_records.jsonl");
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ecbench-app -am test -Dtest=RunDirectoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（1 test）

- [ ] **Step 5: 写失败测试（evaluation 侧布局探测）**

新建 `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/RunLayoutResolverTest.java`：

```java
package io.github.ecommercebench.evaluation.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RunLayoutResolver 测试：逐谈判记录路径在 organized 与 legacy 两种布局下都能定位。 */
class RunLayoutResolverTest {

    private final RunLayoutResolver resolver = new RunLayoutResolver();

    @Test
    void organizedLayoutResolvesUnderMetricsSubdir(@TempDir Path session) throws Exception {
        Files.createDirectories(session.resolve("metrics"));

        RunLayoutResolver.RunLayout layout = resolver.resolve(session);

        assertThat(layout.organized()).isTrue();
        assertThat(layout.negotiationRecordsJsonl(2))
                .isEqualTo(session.resolve("metrics/run_2_negotiation_records.jsonl"));
    }

    @Test
    void legacyFlatLayoutResolvesUnderSessionRoot(@TempDir Path session) {
        RunLayoutResolver.RunLayout layout = resolver.resolve(session);

        assertThat(layout.organized()).isFalse();
        assertThat(layout.negotiationRecordsJsonl(0))
                .isEqualTo(session.resolve("run_0_negotiation_records.jsonl"));
    }
}
```

- [ ] **Step 6: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=RunLayoutResolverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 方法 negotiationRecordsJsonl(int)`

- [ ] **Step 7: 实现 evaluation 侧路径方法**

在 `RunLayoutResolver.java` 的 `RunLayout` record 内、`negotiationMetricsJson(int)` 之后插入：

```java
        public Path negotiationRecordsJsonl(int runIndex) {
            return metricsDir.resolve("run_" + runIndex + "_negotiation_records.jsonl");
        }
```

- [ ] **Step 8: 运行两个模块的测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=RunLayoutResolverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 tests）

- [ ] **Step 9: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/log/RunDirectory.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/log/RunDirectoryTest.java \
        ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/RunLayoutResolver.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/RunLayoutResolverTest.java
git commit -m "feat(log): add negotiation_records.jsonl path contract for §E.6 metrics"
```

---

### Task 2: 逐谈判记录的行模型与读取器

**Files:**
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/NegotiationRecordRow.java`
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/NegotiationRecordJsonlReader.java`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/NegotiationRecordJsonlReaderTest.java`

**Interfaces:**
- Produces:
  - `NegotiationRecordRow(String supplier, String skuId, String supplierType, String supplierFamily, Double referencePrice, Double costFloor, Double initialOffer, Double finalPrice, String outcome, String terminatedBy, Integer dayStarted, Integer dayConcluded, Integer rounds, List<String> criticalViolations, List<Double> agentPrices, List<Double> supplierPrices)` —— 三个 List 组件在构造器内做 null→空列表 + 不可变拷贝
  - `NegotiationRecordJsonlReader()` / `NegotiationRecordJsonlReader(ObjectMapper)`
  - `NegotiationRecordJsonlReader.read(Path file) → List<NegotiationRecordRow>`；文件不存在 → `List.of()`；空行与坏行跳过不中断
- Consumes: Task 1 的 `RunLayout.negotiationRecordsJsonl(int)`（测试里用不到，仅路径约定一致）

- [ ] **Step 1: 写失败测试**

新建 `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/NegotiationRecordJsonlReaderTest.java`：

```java
package io.github.ecommercebench.evaluation.io;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 逐谈判记录读取器测试：字段解析、null 容忍、坏行跳过、文件缺失降级为空列表。 */
class NegotiationRecordJsonlReaderTest {

    private final NegotiationRecordJsonlReader reader = new NegotiationRecordJsonlReader();

    @Test
    void parsesAllFieldsOfAnAgreementRow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");
        Files.writeString(
                file,
                "{\"supplier\":\"oak@a.com\",\"sku_id\":\"sku1\",\"supplier_type\":\"good\","
                        + "\"supplier_family\":\"Candid\",\"reference_price\":100.0,\"cost_floor\":50.0,"
                        + "\"initial_offer\":100.0,\"final_price\":90.0,\"outcome\":\"Agreement\","
                        + "\"terminated_by\":\"SupplierAccept\",\"day_started\":3,\"day_concluded\":4,"
                        + "\"rounds\":5,\"critical_violations\":[\"ResViol\"],"
                        + "\"agent_prices\":[60.0,75.0],\"supplier_prices\":[100.0,95.0]}\n");

        List<NegotiationRecordRow> rows = reader.read(file);

        assertThat(rows).hasSize(1);
        NegotiationRecordRow row = rows.get(0);
        assertThat(row.supplier()).isEqualTo("oak@a.com");
        assertThat(row.skuId()).isEqualTo("sku1");
        assertThat(row.supplierType()).isEqualTo("good");
        assertThat(row.supplierFamily()).isEqualTo("Candid");
        assertThat(row.referencePrice()).isEqualTo(100.0);
        assertThat(row.costFloor()).isEqualTo(50.0);
        assertThat(row.initialOffer()).isEqualTo(100.0);
        assertThat(row.finalPrice()).isEqualTo(90.0);
        assertThat(row.outcome()).isEqualTo("Agreement");
        assertThat(row.terminatedBy()).isEqualTo("SupplierAccept");
        assertThat(row.dayStarted()).isEqualTo(3);
        assertThat(row.dayConcluded()).isEqualTo(4);
        assertThat(row.rounds()).isEqualTo(5);
        assertThat(row.criticalViolations()).containsExactly("ResViol");
        assertThat(row.agentPrices()).containsExactly(60.0, 75.0);
        assertThat(row.supplierPrices()).containsExactly(100.0, 95.0);
    }

    @Test
    void toleratesMissingNullableFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");
        Files.writeString(
                file,
                "{\"supplier\":\"bad@a.com\",\"sku_id\":\"sku2\",\"supplier_type\":\"bad\","
                        + "\"reference_price\":80.0,\"cost_floor\":40.0,\"outcome\":\"Disagreement\"}\n");

        NegotiationRecordRow row = reader.read(file).get(0);

        assertThat(row.finalPrice()).isNull();
        assertThat(row.dayConcluded()).isNull();
        assertThat(row.dayStarted()).isNull();
        assertThat(row.rounds()).isNull();
        assertThat(row.supplierFamily()).isNull();
        assertThat(row.criticalViolations()).isEmpty();
        assertThat(row.agentPrices()).isEmpty();
        assertThat(row.supplierPrices()).isEmpty();
    }

    @Test
    void skipsBlankAndMalformedLinesWithoutFailing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");
        Files.writeString(
                file,
                "\n"
                        + "{不是 JSON\n"
                        + "{\"supplier\":\"ok@a.com\",\"sku_id\":\"s\",\"supplier_type\":\"good\"}\n"
                        + "[1,2,3]\n");

        List<NegotiationRecordRow> rows = reader.read(file);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).supplier()).isEqualTo("ok@a.com");
    }

    @Test
    void missingFileYieldsEmptyList(@TempDir Path dir) {
        assertThat(reader.read(dir.resolve("absent.jsonl"))).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=NegotiationRecordJsonlReaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 NegotiationRecordRow` / `NegotiationRecordJsonlReader`

- [ ] **Step 3: 实现行模型**

新建 `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/NegotiationRecordRow.java`：

```java
package io.github.ecommercebench.evaluation.model;

import java.util.List;

/**
 * 逐谈判原始记录 JSONL 的一行（{@code metrics/run_{i}_negotiation_records.jsonl}）。
 *
 * <p>由 {@code ecbench-app} 的写入器在 run 收尾时落盘，供评估层按论文 §E.6 重算学习维度指标。价格字段以 {@code Double}
 * 承载：{@code finalPrice} 为 null 表示未成交（论文的 {@code f_z = ⊥}），{@code dayConcluded} 为 null
 * 表示无结束日（§E.6 过滤规则 2 会剔除）。三个列表组件在构造器内做 null 归一与不可变拷贝。
 */
public record NegotiationRecordRow(
        String supplier,
        String skuId,
        String supplierType,
        String supplierFamily,
        Double referencePrice,
        Double costFloor,
        Double initialOffer,
        Double finalPrice,
        String outcome,
        String terminatedBy,
        Integer dayStarted,
        Integer dayConcluded,
        Integer rounds,
        List<String> criticalViolations,
        List<Double> agentPrices,
        List<Double> supplierPrices) {

    public NegotiationRecordRow {
        criticalViolations = criticalViolations == null ? List.of() : List.copyOf(criticalViolations);
        agentPrices = agentPrices == null ? List.of() : List.copyOf(agentPrices);
        supplierPrices = supplierPrices == null ? List.of() : List.copyOf(supplierPrices);
    }

    /** 是否为达成一致的成交（结局为 Agreement 且记录了成交价）。 */
    public boolean isAgreement() {
        return "Agreement".equals(outcome) && finalPrice != null;
    }
}
```

- [ ] **Step 4: 实现读取器**

新建 `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/NegotiationRecordJsonlReader.java`：

```java
package io.github.ecommercebench.evaluation.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 逐谈判原始记录 JSONL 读取器（论文 §E.6 学习维度指标的唯一输入）。
 *
 * <p>文件不存在时返回空列表（旧产物没有该文件，属正常情况而非错误）；空行、非法 JSON 行与非对象行**跳过而不中断**，与
 * {@code ChatboxExtractor} 对半写入行的处理一致。缺失字段一律容忍为 null / 空列表，由下游过滤器决定是否剔除。
 */
public final class NegotiationRecordJsonlReader {

    private final ObjectMapper mapper;

    public NegotiationRecordJsonlReader() {
        this(new ObjectMapper());
    }

    public NegotiationRecordJsonlReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取全部可用行；文件缺失返回空列表。 */
    public List<NegotiationRecordRow> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<NegotiationRecordRow> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node = parse(trimmed);
                if (node == null || !node.isObject()) {
                    continue;
                }
                rows.add(toRow(node));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取逐谈判记录: " + file, exception);
        }
        return rows;
    }

    private JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (IOException exception) {
            return null;
        }
    }

    private NegotiationRecordRow toRow(JsonNode node) {
        return new NegotiationRecordRow(
                text(node, "supplier"),
                text(node, "sku_id"),
                text(node, "supplier_type"),
                text(node, "supplier_family"),
                number(node, "reference_price"),
                number(node, "cost_floor"),
                number(node, "initial_offer"),
                number(node, "final_price"),
                text(node, "outcome"),
                text(node, "terminated_by"),
                integer(node, "day_started"),
                integer(node, "day_concluded"),
                integer(node, "rounds"),
                texts(node, "critical_violations"),
                numbers(node, "agent_prices"),
                numbers(node, "supplier_prices"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asDouble();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static List<String> texts(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(value.size());
        value.forEach(item -> out.add(item.asText()));
        return out;
    }

    private static List<Double> numbers(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(value.size());
        value.forEach(item -> out.add(item.isNumber() ? item.asDouble() : null));
        return out;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=NegotiationRecordJsonlReaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 tests）

- [ ] **Step 6: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/NegotiationRecordRow.java \
        ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/io/NegotiationRecordJsonlReader.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/io/NegotiationRecordJsonlReaderTest.java
git commit -m "feat(evaluation): read per-negotiation records jsonl for §E.6 metrics"
```

---

### Task 3: 逐谈判记录写入器

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/log/NegotiationRecordJsonlWriter.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/log/NegotiationRecordJsonlWriterTest.java`

**Interfaces:**
- Consumes: `io.github.ecommercebench.opponent.model.NegotiationRecord`（既有，零改动）、`io.github.ecommercebench.domain.money.Money.amount() → BigDecimal`
- Produces:
  - `NegotiationRecordJsonlWriter(Path file, ObjectMapper mapper)`
  - `writeAll(List<NegotiationRecord> records) → void`：整文件覆盖写（`CREATE` + `TRUNCATE_EXISTING`），每条一行；空列表 → 空文件
  - `close() → void`：幂等 no-op（无长持流，与 `MetricsJsonWriter` 一致）
  - 落盘字段名与 Task 2 读取器逐一对应（`supplier`/`sku_id`/`supplier_type`/`supplier_family`/`reference_price`/`cost_floor`/`initial_offer`/`final_price`/`outcome`/`terminated_by`/`day_started`/`day_concluded`/`rounds`/`critical_violations`/`agent_prices`/`supplier_prices`）

- [ ] **Step 1: 写失败测试（含与读取器的 round-trip）**

新建 `ecbench-app/src/test/java/io/github/ecommercebench/app/log/NegotiationRecordJsonlWriterTest.java`：

```java
package io.github.ecommercebench.app.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.evaluation.io.NegotiationRecordJsonlReader;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;
import io.github.ecommercebench.opponent.model.NegotiationRecord;
import io.github.ecommercebench.opponent.model.SupplierFamily;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 逐谈判记录写入器测试：字段齐备、空记录写空文件、与评估层读取器 round-trip 一致。 */
class NegotiationRecordJsonlWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NegotiationRecord record(String supplier, String sku, String type) {
        NegotiationRecord record =
                new NegotiationRecord(
                        supplier,
                        sku,
                        type,
                        SupplierFamily.CANDID,
                        money("100.00"),
                        money("50.00"),
                        money("100.00"),
                        3);
        record.recordAgentOffer(money("70.00"));
        record.recordSupplierOffer(money("95.00"));
        record.recordAgentOffer(money("90.00"));
        record.complete("Agreement", money("90.00"), "SupplierAccept", 4);
        return record;
    }

    private static Money money(String amount) {
        return Money.of(amount);
    }

    @Test
    void writesOneLinePerRecordWithAllFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER)
                .writeAll(List.of(record("oak@a.com", "sku1", "good"), record("bad@a.com", "sku2", "bad")));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .contains("\"supplier\":\"oak@a.com\"")
                .contains("\"sku_id\":\"sku1\"")
                .contains("\"supplier_type\":\"good\"")
                .contains("\"supplier_family\":\"Candid\"")
                .contains("\"reference_price\":100.0")
                .contains("\"cost_floor\":50.0")
                .contains("\"initial_offer\":100.0")
                .contains("\"final_price\":90.0")
                .contains("\"outcome\":\"Agreement\"")
                .contains("\"terminated_by\":\"SupplierAccept\"")
                .contains("\"day_started\":3")
                .contains("\"day_concluded\":4")
                .contains("\"rounds\":4")
                .contains("\"critical_violations\":[]")
                .contains("\"agent_prices\":[70.0,90.0]")
                .contains("\"supplier_prices\":[95.0]");
    }

    @Test
    void writesNullForAbsentPriceAndDay(@TempDir Path dir) throws Exception {
        NegotiationRecord open =
                new NegotiationRecord(
                        "x@a.com",
                        "sku",
                        "good",
                        SupplierFamily.CANDID,
                        money("100.00"),
                        money("50.00"),
                        money("100.00"),
                        null);
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER).writeAll(List.of(open));

        assertThat(Files.readString(file))
                .contains("\"final_price\":null")
                .contains("\"day_started\":null")
                .contains("\"day_concluded\":null")
                .contains("\"outcome\":null");
    }

    @Test
    void emptyRecordListProducesEmptyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER).writeAll(List.of());

        assertThat(file).exists();
        assertThat(Files.readString(file)).isEmpty();
    }

    @Test
    void roundTripsThroughEvaluationReader(@TempDir Path dir) {
        Path file = dir.resolve("run_0_negotiation_records.jsonl");

        new NegotiationRecordJsonlWriter(file, MAPPER).writeAll(List.of(record("oak@a.com", "sku1", "good")));
        List<NegotiationRecordRow> rows = new NegotiationRecordJsonlReader().read(file);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isAgreement()).isTrue();
        assertThat(rows.get(0).referencePrice()).isEqualTo(100.0);
        assertThat(rows.get(0).costFloor()).isEqualTo(50.0);
        assertThat(rows.get(0).finalPrice()).isEqualTo(90.0);
        assertThat(rows.get(0).dayConcluded()).isEqualTo(4);
    }

    @Test
    void closeIsIdempotent(@TempDir Path dir) {
        NegotiationRecordJsonlWriter writer =
                new NegotiationRecordJsonlWriter(dir.resolve("run_0_negotiation_records.jsonl"), MAPPER);

        writer.close();
        writer.close();
    }
}
```

已核验的两个事实（断言已按此写定，不要再改）：`Money.of(String)` 存在于 `ecbench-domain/.../money/Money.java:33`，紧凑构造器把金额规整为两位小数，故 `money("100.00")` 经 `amount().doubleValue()` 落盘为 `100.0`；`rounds` 为 **4**，因为 `recordAgentOffer`（×2）、`recordSupplierOffer`（×1）与 `complete`（×1）各自 `rounds++`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-app -am test -Dtest=NegotiationRecordJsonlWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 NegotiationRecordJsonlWriter`

- [ ] **Step 3: 实现写入器**

新建 `ecbench-app/src/main/java/io/github/ecommercebench/app/log/NegotiationRecordJsonlWriter.java`：

```java
package io.github.ecommercebench.app.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.NegotiationRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 逐谈判原始记录 JSONL 写入器（论文 §E.6 学习维度指标的重算输入）。
 *
 * <p>把 {@link NegotiationRecord} 逐行落盘为 {@code metrics/run_{i}_negotiation_records.jsonl}，字段名与评估层
 * {@code NegotiationRecordJsonlReader} 一一对应。这是 Java 侧附加产物：Python 参考实现不产出该文件，既有产物格式不受影响。
 * {@link #writeAll} 每次整文件覆盖写并自行开闭流，故 {@link #close()} 是幂等 no-op（与 {@code MetricsJsonWriter} 一致）。
 */
public final class NegotiationRecordJsonlWriter implements AutoCloseable {

    private final Path file;
    private final ObjectMapper mapper;

    public NegotiationRecordJsonlWriter(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /** 覆盖写出全部已完成谈判记录；空列表写出空文件（保持产物存在性可判定）。 */
    public void writeAll(List<NegotiationRecord> records) {
        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            for (NegotiationRecord record : records) {
                writer.write(mapper.writeValueAsString(node(record)));
                writer.write("\n");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("写入逐谈判记录失败: " + file, exception);
        }
    }

    private ObjectNode node(NegotiationRecord record) {
        ObjectNode node = mapper.createObjectNode();
        node.put("supplier", record.supplierName());
        node.put("sku_id", record.skuId());
        node.put("supplier_type", record.supplierType());
        node.put(
                "supplier_family",
                record.supplierFamily() == null ? null : record.supplierFamily().displayName());
        putMoney(node, "reference_price", record.referencePrice());
        putMoney(node, "cost_floor", record.costFloor());
        putMoney(node, "initial_offer", record.initialOffer());
        putMoney(node, "final_price", record.finalPrice());
        node.put("outcome", record.outcome());
        node.put("terminated_by", record.terminatedBy());
        putInteger(node, "day_started", record.dayStarted());
        putInteger(node, "day_concluded", record.dayConcluded());
        node.put("rounds", record.rounds());
        node.set("critical_violations", strings(record.criticalViolations()));
        node.set("agent_prices", money(record.agentPrices()));
        node.set("supplier_prices", money(record.supplierPrices()));
        return node;
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private ArrayNode money(List<Money> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(value -> array.add(value.amount().doubleValue()));
        return array;
    }

    private static void putMoney(ObjectNode node, String field, Money value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.amount().doubleValue());
        }
    }

    private static void putInteger(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.intValue());
        }
    }

    /** 无长持资源需释放（{@link #writeAll} 自行开闭流），仅满足 AutoCloseable 契约。 */
    @Override
    public void close() {
        // 幂等 no-op。
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ecbench-app -am test -Dtest=NegotiationRecordJsonlWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 tests）

- [ ] **Step 5: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/log/NegotiationRecordJsonlWriter.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/log/NegotiationRecordJsonlWriterTest.java
git commit -m "feat(log): persist per-negotiation records as jsonl artifact"
```

---

### Task 4: 把写入器接进 run 收尾流程

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/log/CompositeRunObserver.java`（构造器、字段、`onRunComplete`、`onFailure`、`close`）
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/run/RunComponentFactory.java:141-142`
- Modify: `ecbench-app/src/test/java/io/github/ecommercebench/app/log/CompositeRunObserverToolCallCountTest.java:65-67`
- Modify: `ecbench-app/src/test/java/io/github/ecommercebench/app/log/CompatibleRunLoggingTest.java:63-67`

**Interfaces:**
- Consumes: Task 3 的 `NegotiationRecordJsonlWriter`、`io.github.ecommercebench.opponent.metrics.NegotiationTracker.completed() → List<NegotiationRecord>`
- Produces: `CompositeRunObserver(RunDirectory directory, int runIndex, SimulationEngine engine, ObjectMapper mapper, Supplier<NegotiationMetrics> negotiationSupplier, Supplier<List<NegotiationRecord>> recordsSupplier)` —— 6 参构造，**不保留** 5 参重载（三个调用点全部显式更新，避免静默写出空文件）

- [ ] **Step 1: 写失败测试（在既有兼容性测试里加断言）**

将 `CompatibleRunLoggingTest.java` 的 `observer(...)` 助手（L63-67）改为 6 参，并补一个 `emptyRecords()` 助手：

```java
    private static CompositeRunObserver observer(Path tempDir, SimulationEngine engine) {
        RunDirectory dir = RunDirectory.create(tempDir, "20260101_080000", "fake/model");
        return new CompositeRunObserver(
                dir,
                0,
                engine,
                MAPPER,
                CompatibleRunLoggingTest::emptyNegotiation,
                CompatibleRunLoggingTest::emptyRecords);
    }

    private static List<NegotiationRecord> emptyRecords() {
        return List.of();
    }
```

在导入区补三行（按字母序插入到现有 `io.github.ecommercebench.*` 导入之间）：

```java
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.model.NegotiationRecord;
import io.github.ecommercebench.opponent.model.SupplierFamily;
```

在该类最后一个 `@Test` 之后新增两个测试（已核对该类现有助手：`newEngine()`、`seedJob()`、`emptyNegotiation()` 均存在；`RunResult` 为 9 参构造，与 L159-169 的现有用法一致）：

```java
    @Test
    void runCompleteWritesNegotiationRecordsArtifact(@TempDir Path tempDir) throws Exception {
        SimulationEngine engine = newEngine();
        RunDirectory dir = RunDirectory.create(tempDir, "20260101_080000", "fake/model");
        try (CompositeRunObserver observer =
                new CompositeRunObserver(
                        dir, 0, engine, MAPPER, CompatibleRunLoggingTest::emptyNegotiation, List::of)) {
            observer.onRunStart(seedJob());
            observer.onRunComplete(
                    new RunResult(
                            TerminationReason.ENV_COMPLETED,
                            "max_days",
                            1,
                            List.of(),
                            1,
                            "2026-01-02",
                            100000.0,
                            0,
                            0));
        }

        Path artifact = dir.negotiationRecordsJsonl(0);
        assertThat(artifact).exists();
        assertThat(Files.readString(artifact)).isEmpty();
    }

    @Test
    void failurePathStillWritesNegotiationRecords(@TempDir Path tempDir) throws Exception {
        SimulationEngine engine = newEngine();
        RunDirectory dir = RunDirectory.create(tempDir, "20260101_080000", "fake/model");
        NegotiationRecord record =
                new NegotiationRecord(
                        "oak@a.com",
                        "sku1",
                        "good",
                        SupplierFamily.CANDID,
                        Money.of("100.00"),
                        Money.of("50.00"),
                        Money.of("100.00"),
                        1);
        record.complete("Agreement", Money.of("90.00"), "SupplierAccept", 2);
        try (CompositeRunObserver observer =
                new CompositeRunObserver(
                        dir,
                        0,
                        engine,
                        MAPPER,
                        CompatibleRunLoggingTest::emptyNegotiation,
                        () -> List.of(record))) {
            observer.onRunStart(seedJob());
            observer.onFailure(new IllegalStateException("boom"));
        }

        Path artifact = dir.negotiationRecordsJsonl(0);
        assertThat(artifact).exists();
        assertThat(Files.readString(artifact)).contains("\"supplier\":\"oak@a.com\"");
    }
```

同时把该类类级 Javadoc 首句的「四类 Python 兼容文件」改为「四类 Python 兼容文件与一份 Java 侧附加的逐谈判记录 JSONL」。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-app -am test -Dtest=CompatibleRunLoggingTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 先编译失败（`构造器 CompositeRunObserver 无法应用到给定类型：要求 5 个参数，找到 6 个`）；完成 Step 3/4 后重跑应 PASS（含 2 个新测试）

- [ ] **Step 3: 改 CompositeRunObserver**

`CompositeRunObserver.java` 的四处改动：

导入区补两行（放在既有 `io.github.ecommercebench.opponent.metrics.NegotiationMetrics` 之后，保持字母序）：

```java
import io.github.ecommercebench.opponent.model.NegotiationRecord;
```

字段区（`negotiationSupplier` 之后）：

```java
    private final NegotiationRecordJsonlWriter records;
    private final Supplier<List<NegotiationRecord>> recordsSupplier;
```

构造器整体替换为：

```java
    /**
     * 按 run 目录与索引装配五类写入器（消息/余额/指标/输出/逐谈判记录），并持有引擎与两个惰性供应器。
     *
     * <p>逐谈判记录以 {@code Supplier} 惰性获取，与谈判子系统装配解耦；它是论文 §E.6 学习维度指标的重算输入。
     */
    public CompositeRunObserver(
            RunDirectory directory,
            int runIndex,
            SimulationEngine engine,
            ObjectMapper mapper,
            Supplier<NegotiationMetrics> negotiationSupplier,
            Supplier<List<NegotiationRecord>> recordsSupplier) {
        this.engine = engine;
        this.negotiationSupplier = negotiationSupplier;
        this.recordsSupplier = recordsSupplier;
        this.messages = new JsonlMessageWriter(directory.messagesJsonl(runIndex), mapper);
        this.balance = new BalanceCsvWriter(directory.balanceCsv(runIndex));
        this.metrics = new MetricsJsonWriter(directory, runIndex, mapper);
        this.outputLog = new OutputLogWriter(directory.outputLog(runIndex));
        this.records = new NegotiationRecordJsonlWriter(directory.negotiationRecordsJsonl(runIndex), mapper);
    }
```

`onRunComplete` 与 `onFailure` 各加一次写出（`onRunComplete` 在 `writeNegotiationMetrics` 之后、`writeAnalysis` 之前；`onFailure` 在既有 try 块内追加）：

```java
    /** 收尾：快照余额，取谈判指标与逐谈判记录并写入，最后连同回撤与工具计数一并写出分析产物。 */
    @Override
    public void onRunComplete(RunResult result) {
        snapshotBalance();
        NegotiationMetrics negotiation = negotiationSupplier.get();
        metrics.writeNegotiationMetrics(negotiation);
        records.writeAll(recordsSupplier.get());
        metrics.writeAnalysis(result, engine, negotiation, maxDrawdown, peakTotal, toolCallCounts);
    }

    /** 失败路径：记一行 [FAILURE]，并尽力写出谈判指标与逐谈判记录（异常被吞掉，以免掩盖原始异常）。 */
    @Override
    public void onFailure(Throwable error) {
        outputLog.write("[FAILURE] " + error);
        try {
            metrics.writeNegotiationMetrics(negotiationSupplier.get());
            records.writeAll(recordsSupplier.get());
        } catch (RuntimeException ignored) {
            // 尽力而为：失败路径下指标缺失不得掩盖原始异常。
        }
    }
```

`close()` 追加一行（并同步更新其 Javadoc 的「四类」为「五类」）：

```java
    /** 依次关闭消息/余额/指标/输出/逐谈判记录五类写入器（各写入器 close 幂等）。 */
    @Override
    public void close() {
        messages.close();
        balance.close();
        metrics.close();
        outputLog.close();
        records.close();
    }
```

类级 Javadoc 首行的「四个兼容写入器（消息 JSONL、余额 CSV、指标 JSON、输出日志）」改为「五个兼容写入器（消息 JSONL、余额 CSV、指标 JSON、输出日志、逐谈判记录 JSONL）」。

- [ ] **Step 4: 改装配点与另一处测试调用点**

`RunComponentFactory.java:141-142` 改为：

```java
        CompositeRunObserver observer =
                new CompositeRunObserver(
                        directory, index, engine, objectMapper, tracker::aggregate, tracker::completed);
```

`CompositeRunObserverToolCallCountTest.java:65-67` 改为：

```java
        try (CompositeRunObserver observer =
                new CompositeRunObserver(
                        dir,
                        0,
                        engine,
                        MAPPER,
                        CompositeRunObserverToolCallCountTest::emptyNegotiation,
                        List::of)) {
```

该测试文件顶部若无 `import java.util.List;` 则补上。

- [ ] **Step 5: 运行 app 模块全部测试确认通过**

Run: `mvn -q -pl ecbench-app -am test`
Expected: PASS，0 failures、0 errors（既有 1 个 skip 保持）

- [ ] **Step 6: 提交**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/log/CompositeRunObserver.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/run/RunComponentFactory.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/log/CompatibleRunLoggingTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/log/CompositeRunObserverToolCallCountTest.java
git commit -m "feat(log): write negotiation records on run completion and failure"
```

---

### Task 5: 学习指标模型与计算器核心（过滤 / 位置 / AnchorRegret / NewLow）

**Files:**
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/LearningMetrics.java`
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/SessionLearning.java`
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsCalculator.java`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsCalculatorTest.java`

**Interfaces:**
- Consumes: Task 2 的 `NegotiationRecordRow`
- Produces（本任务定义的完整契约，Task 6/7 只填充计算、不改签名）:
  - `LearningMetrics(Double anchorRegret, Double newLow, Double anchorRatio, Double zEpisode, Double newLowRatioZ, Double nullMean, Double nullStd, Double seHalfLift, Double seTrend, Double fraudHalfLift, int repeatPairs, int reorders)`
  - `SessionLearning(Double anchorRatio, Double anchorRegret, Double newLow, Double newLowRatioZ, Double zAnchor, Double seHalfLift, Double seTrend, Double fraudHalfLift, int episodes, int repeatPairs, int reorders)`
  - `LearningMetricsCalculator()`（seed=20260101, B=400）/ `LearningMetricsCalculator(long seed, int replicates)`
  - `LearningMetricsCalculator.calculate(List<NegotiationRecordRow> rows) → LearningMetrics`
  - 本任务结束时 `anchorRatio`/`zEpisode`/`newLowRatioZ`/`nullMean`/`nullStd`（Task 6）与 `seHalfLift`/`seTrend`/`fraudHalfLift`（Task 7）仍为 null，其余字段已可用

- [ ] **Step 1: 写失败测试**

新建 `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsCalculatorTest.java`：

```java
package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 论文 §E.6 学习维度计算器测试。
 *
 * <p>主 fixture 为单个诚信 pair（参考价 100、成本底价 50 → Δ=50），三次成交价 90/80/95，对应位置 π=0.8/0.6/0.9：
 * re-order 1 的 max(0, 0.6−0.8)=0 且创新低；re-order 2 的 max(0, 0.9−0.6)=0.3 且未创新低。故
 * AnchorRegret=(0+0.3)/2=0.15、NewLow=(1+0)/2=0.5、|R|=2。
 */
class LearningMetricsCalculatorTest {

    private final LearningMetricsCalculator calculator = new LearningMetricsCalculator();

    private static NegotiationRecordRow row(
            String supplier,
            String sku,
            String type,
            double reference,
            double floor,
            Double price,
            String outcome,
            Integer day) {
        return new NegotiationRecordRow(
                supplier,
                sku,
                type,
                null,
                reference,
                floor,
                reference,
                price,
                outcome,
                null,
                day == null ? null : day - 1,
                day,
                3,
                List.of(),
                List.of(),
                List.of());
    }

    private static NegotiationRecordRow agree(
            String supplier, String sku, double reference, double floor, double price, int day) {
        return row(supplier, sku, "good", reference, floor, price, "Agreement", day);
    }

    private static NegotiationRecordRow disagree(
            String supplier, String sku, double reference, double floor, int day) {
        return row(supplier, sku, "good", reference, floor, null, "Disagreement", day);
    }

    /** 主 fixture：一个诚信 pair 的三次成交（π = 0.8 / 0.6 / 0.9）。 */
    private static List<NegotiationRecordRow> mainPair() {
        return new ArrayList<>(
                List.of(
                        agree("S1", "SKU1", 100.0, 50.0, 90.0, 1),
                        agree("S1", "SKU1", 100.0, 50.0, 80.0, 2),
                        agree("S1", "SKU1", 100.0, 50.0, 95.0, 3)));
    }

    @Test
    void computesAnchorRegretAndNewLowForOneRepeatPair() {
        LearningMetrics metrics = calculator.calculate(mainPair());

        assertThat(metrics.repeatPairs()).isEqualTo(1);
        assertThat(metrics.reorders()).isEqualTo(2);
        assertThat(metrics.anchorRegret()).isCloseTo(0.15, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void poolsReordersAcrossPairs() {
        List<NegotiationRecordRow> rows = mainPair();
        // 第二个诚信 pair：Δ=100，两次同价成交 → π=0.8/0.8，regret 0、未创新低
        rows.add(agree("S2", "SKU2", 200.0, 100.0, 180.0, 1));
        rows.add(agree("S2", "SKU2", 200.0, 100.0, 180.0, 5));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.repeatPairs()).isEqualTo(2);
        assertThat(metrics.reorders()).isEqualTo(3);
        assertThat(metrics.anchorRegret()).isCloseTo(0.1, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(1.0 / 3.0, within(1e-9));
    }

    @Test
    void clipsPositionsOutsideBargainingRange() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        // π 会被截断：p=120 → (120−50)/50 = 1.4 → 1.0；p=40 → −0.2 → 0.0
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 120.0, 1));
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 40.0, 2));

        LearningMetrics metrics = calculator.calculate(rows);

        // π = 1.0 → 0.0：regret = max(0, 0−1) = 0；NewLow = 1[0.0 < 1.0−0.01] = 1
        assertThat(metrics.anchorRegret()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void appliesTheFourPaperFilters() {
        List<NegotiationRecordRow> rows = mainPair();
        // 过滤 1：欺诈 pair（即使重复成交也不计入）
        rows.add(row("BAD", "SKU9", "bad", 100.0, 50.0, 90.0, "Agreement", 1));
        rows.add(row("BAD", "SKU9", "bad", 100.0, 50.0, 95.0, "Agreement", 2));
        // 过滤 2：无结束日的成交
        rows.add(row("S3", "SKU3", "good", 100.0, 50.0, 90.0, "Agreement", null));
        rows.add(row("S3", "SKU3", "good", 100.0, 50.0, 85.0, "Agreement", null));
        // 过滤 3：Δ ≤ 0（参考价 == 成本底价）
        rows.add(agree("S4", "SKU4", 50.0, 50.0, 50.0, 1));
        rows.add(agree("S4", "SKU4", 50.0, 50.0, 50.0, 2));
        // 过滤 4：只成交一次的 pair
        rows.add(agree("S5", "SKU5", 100.0, 50.0, 70.0, 1));
        // 未成交的会话不进入 anchoring
        rows.add(disagree("S1", "SKU1", 100.0, 50.0, 4));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.repeatPairs()).isEqualTo(1);
        assertThat(metrics.reorders()).isEqualTo(2);
        assertThat(metrics.anchorRegret()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void noRepeatPairYieldsAllNullLearningMetrics() {
        LearningMetrics metrics =
                calculator.calculate(List.of(agree("S1", "SKU1", 100.0, 50.0, 90.0, 1)));

        assertThat(metrics.repeatPairs()).isZero();
        assertThat(metrics.reorders()).isZero();
        assertThat(metrics.anchorRegret()).isNull();
        assertThat(metrics.newLow()).isNull();
        assertThat(metrics.anchorRatio()).isNull();
        assertThat(metrics.zEpisode()).isNull();
    }

    @Test
    void emptyInputYieldsAllNullLearningMetrics() {
        LearningMetrics metrics = calculator.calculate(List.of());

        assertThat(metrics.repeatPairs()).isZero();
        assertThat(metrics.anchorRegret()).isNull();
    }

    @Test
    void sortsReordersByConclusionDayNotInputOrder() {
        // 输入顺序故意与日期相反：按 day 升序后 π = 0.8 → 0.6，regret = 0、NewLow = 1
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 80.0, 9));
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 90.0, 2));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.anchorRegret()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.newLow()).isCloseTo(1.0, within(1e-9));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 LearningMetrics` / `LearningMetricsCalculator`

- [ ] **Step 3: 实现两个模型**

新建 `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/LearningMetrics.java`：

```java
package io.github.ecommercebench.evaluation.model;

/**
 * 单个 episode（= 单个 run）的论文 §E.6 学习维度指标。
 *
 * <p>全部数值字段为 {@code Double} 且以 null 表示「不适用」：该 episode 无重复采购 pair（§E.6 过滤规则 4）、零分布退化
 * （{@code nullMean}/{@code nullStd} 为 0）或半年比较样本不足（诚信已结束会话数 &lt; 4）时即为 null。计数字段恒有值。
 * 本 record 不做四舍五入，展示层负责取整口径。
 */
public record LearningMetrics(
        Double anchorRegret,
        Double newLow,
        Double anchorRatio,
        Double zEpisode,
        Double newLowRatioZ,
        Double nullMean,
        Double nullStd,
        Double seHalfLift,
        Double seTrend,
        Double fraudHalfLift,
        int repeatPairs,
        int reorders) {}
```

新建 `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/SessionLearning.java`：

```java
package io.github.ecommercebench.evaluation.model;

/**
 * 会话级（跨该会话全部 run）的论文 §E.6 学习维度聚合。
 *
 * <p>按论文口径：{@code anchorRatio} 取各 episode 比率的均值（**不是**汇总均值之比）；{@code zAnchor} 用 Stouffer 法则合并
 * 各 episode 的 z（{@code (1/√k) Σ z_r}）；其余指标取各 episode 均值。{@code episodes} 即论文的 {@code k}：有重复 pair
 * 且零分布非退化的 run 数。null 语义与 {@link LearningMetrics} 一致；全 null 时 {@code episodes} 为 0。
 */
public record SessionLearning(
        Double anchorRatio,
        Double anchorRegret,
        Double newLow,
        Double newLowRatioZ,
        Double zAnchor,
        Double seHalfLift,
        Double seTrend,
        Double fraudHalfLift,
        int episodes,
        int repeatPairs,
        int reorders) {}
```

- [ ] **Step 4: 实现计算器（本任务只到水平统计量）**

新建 `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsCalculator.java`：

```java
package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 论文 §E.6「长周期学习」指标计算器（纯函数，无 IO、无静态可变状态）。
 *
 * <p>输入为一个 episode 的逐谈判记录，输出 {@link LearningMetrics}。序贯价格纪律部分：只保留诚信、已成交、有结束日且议价区间
 * {@code Δ = 参考价 − 成本底价 > 0} 的 (supplier, SKU) pair，且要求该 pair 至少成交两次；把每次成交价映射为区间内位置
 * {@code π}（截断到 [0,1]），从第二笔起与历史最优 {@code m} 比较，得到 {@code AnchorRegret} 与创新低率 {@code NewLow}
 * （容差 ε=0.01）；再用 B=400 次固定种子的独立置换构造零分布，得到 {@code AnchorRatio} 与置换 z。半年比较部分见 §E.6：
 * 剩余半期提升、逐日盈余趋势与欺诈半期提升，三者均以「正 = 改善」为方向。
 *
 * <p>确定性保证：pair 迭代序为 key 的字典序（{@link TreeMap}），随机源为单个 {@link Random}（种子默认 20260101），
 * 因此同输入 + 同种子逐位可复现。零分布的离散度是**总体**标准差（除以 B），与档案其他处的样本标准差刻意不同，不可互比。
 */
public final class LearningMetricsCalculator {

    /** 论文 §E.6 的创新低容差 ε。 */
    private static final double EPSILON = 0.01;

    /** 论文 §E.6 的置换重复次数 B。 */
    private static final int DEFAULT_REPLICATES = 400;

    /** 默认随机种子：保证同一产物多次计算结果逐位一致。 */
    private static final long DEFAULT_SEED = 20260101L;

    /** 半年比较所需的最小诚信已结束会话数 n（论文 §E.6：n ≥ 4）。 */
    private static final int MIN_SESSIONS_FOR_HALF = 4;

    private final long seed;
    private final int replicates;

    public LearningMetricsCalculator() {
        this(DEFAULT_SEED, DEFAULT_REPLICATES);
    }

    public LearningMetricsCalculator(long seed, int replicates) {
        this.seed = seed;
        this.replicates = replicates;
    }

    /** 计算一个 episode 的全部 §E.6 指标；不适用者为 null。 */
    public LearningMetrics calculate(List<NegotiationRecordRow> rows) {
        List<Pair> pairs = honestRepeatPairs(rows);
        int reorders = pairs.stream().mapToInt(Pair::reorders).sum();
        if (pairs.isEmpty()) {
            return new LearningMetrics(
                    null, null, null, null, null, null, null, null, null, null, 0, 0);
        }
        Double regret = levelStatistic(pairs, true);
        Double newLow = levelStatistic(pairs, false);
        return new LearningMetrics(
                regret, newLow, null, null, null, null, null, null, null, null, pairs.size(), reorders);
    }

    /**
     * 一个诚信 (supplier, SKU) pair 的位置序列。
     *
     * @param key 字典序排序键（{@code supplier + '\u0000' + skuId}）
     * @param positions 按结束日升序的截断位置 π
     */
    private record Pair(String key, double[] positions) {

        int reorders() {
            return positions.length - 1;
        }
    }

    /** 应用论文 §E.6 的四项过滤，返回按 key 字典序排列的重复采购 pair。 */
    private static List<Pair> honestRepeatPairs(List<NegotiationRecordRow> rows) {
        Map<String, List<NegotiationRecordRow>> grouped = new TreeMap<>();
        for (NegotiationRecordRow row : rows) {
            if (!usableForAnchoring(row)) {
                continue;
            }
            grouped.computeIfAbsent(row.supplier() + "\u0000" + row.skuId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        List<Pair> pairs = new ArrayList<>();
        for (Map.Entry<String, List<NegotiationRecordRow>> entry : grouped.entrySet()) {
            List<NegotiationRecordRow> records = entry.getValue();
            if (records.size() < 2) {
                continue;
            }
            // List.sort 是稳定排序：同一结束日保持 jsonl 出现序
            records.sort(Comparator.comparing(NegotiationRecordRow::dayConcluded));
            double floor = records.get(0).costFloor();
            double delta = records.get(0).referencePrice() - floor;
            double[] positions = new double[records.size()];
            for (int i = 0; i < records.size(); i++) {
                positions[i] = clip((records.get(i).finalPrice() - floor) / delta);
            }
            pairs.add(new Pair(entry.getKey(), positions));
        }
        return pairs;
    }

    /** §E.6 过滤规则 1–3：诚信、已成交且有成交价、有结束日、议价区间为正。 */
    private static boolean usableForAnchoring(NegotiationRecordRow row) {
        return "good".equals(row.supplierType())
                && row.isAgreement()
                && row.dayConcluded() != null
                && row.referencePrice() != null
                && row.costFloor() != null
                && row.referencePrice() - row.costFloor() > 0.0;
    }

    /** 截断到 [0,1]，与论文「positions here are clipped, unlike SE」一致。 */
    private static double clip(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 跨 pair 汇总水平统计量：regret=true 取 max(0, π_t − m_t)，否则取 1[π_t < m_t − ε]；分母为 |R|。 */
    private static Double levelStatistic(List<Pair> pairs, boolean regret) {
        double sum = 0.0;
        int count = 0;
        for (Pair pair : pairs) {
            double[] part = accumulate(pair.positions(), regret);
            sum += part[0];
            count += (int) part[1];
        }
        return count == 0 ? null : sum / count;
    }

    /** 对单条位置序列累计 re-order 统计量，返回 [求和, 计数]；历史最优 m 随扫描推进。 */
    private static double[] accumulate(double[] positions, boolean regret) {
        double sum = 0.0;
        int count = 0;
        double best = positions[0];
        for (int t = 1; t < positions.length; t++) {
            double current = positions[t];
            sum += regret ? Math.max(0.0, current - best) : (current < best - EPSILON ? 1.0 : 0.0);
            count++;
            best = Math.min(best, current);
        }
        return new double[] {sum, count};
    }

    /** Fisher–Yates 洗牌副本，使用共享 Random 保证同种子下的确定性。 */
    private static double[] shuffle(double[] source, Random random) {
        double[] copy = source.clone();
        for (int i = copy.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            double swap = copy[i];
            copy[i] = copy[j];
            copy[j] = swap;
        }
        return copy;
    }

    /** 均值与总体标准差（除以 B），返回 [mean, populationStd]。 */
    private static double[] moments(double[] values) {
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;
        double sumSquares = 0.0;
        for (double value : values) {
            sumSquares += (value - mean) * (value - mean);
        }
        return new double[] {mean, Math.sqrt(sumSquares / values.length)};
    }
}
```

说明：`shuffle` 与 `moments` 在本任务已定义但仅被 Task 6 使用；为避免 Checkstyle/编译告警（未使用的 private 方法会触发 `unused` 类检查，取决于配置），**本任务的 `calculate` 暂不调用它们是可以的**，但更稳妥的做法是在本任务就把 Task 6 的 `permutationNull` 一并写入（见 Task 6 Step 3），使三个 private 方法都被引用。若本任务的 `mvn test` 因未使用 private 方法报错，直接把 Task 6 Step 3 的 `permutationNull` 方法与 `calculate` 的对应调用提前合并进来即可，测试断言不变。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（7 tests）

- [ ] **Step 6: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/LearningMetrics.java \
        ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/SessionLearning.java \
        ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsCalculator.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsCalculatorTest.java
git commit -m "feat(evaluation): compute §E.6 anchor regret and new-low rate"
```

---

### Task 6: 置换零分布、AnchorRatio 与置换 z

**Files:**
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsCalculator.java`（`calculate` 重写、`levelStatistic` 返回类型改 `double`、新增 `permutationNull`）
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsCalculatorTest.java`（追加 5 个测试）

**Interfaces:**
- Consumes: Task 5 的 `Pair`、`accumulate`、`shuffle`、`moments`、`levelStatistic`
- Produces: `calculate` 现在填充 `anchorRatio`、`zEpisode`、`newLowRatioZ`、`nullMean`、`nullStd`（`seHalfLift`/`seTrend`/`fraudHalfLift` 仍为 null，Task 7 填充）；`levelStatistic(List<Pair>, boolean) → double`（原 `Double`，`count == 0` 时返回 `0.0`）

**手算依据（主 fixture，π = [0.8, 0.6, 0.9]，|R| = 2）**：全部 6 个置换的 regret 之和为 `0.3+0.1+0.5+0.5+0+0.2 = 1.6`，每置换除以 `|R|=2` 后均值为 `1.6/6/2 = 0.13333…`；NewLow 的 6 个置换值为 `0.5,0.5,0,0,1.0,0.5`，均值 `2.5/6 = 0.41666…`。观测值 regret=0.15、newLow=0.5，故 `AnchorRatio ≈ 1.125`（>1，即比自身价格随机排列更贵，正是论文所述「未修订的早期锚点」）。断言用**解析值 ± 容差**（B=400 的抽样均值 1σ ≈ 0.005）+ **内部一致性恒等式**（不依赖具体 PRNG 输出）。

- [ ] **Step 1: 追加失败测试**

在 `LearningMetricsCalculatorTest` 末尾（最后一个 `}` 之前）追加：

```java
    @Test
    void permutationNullConvergesToAnalyticMean() {
        LearningMetrics metrics = calculator.calculate(mainPair());

        // 6 个置换的解析均值：regret = 1.6/6/2 = 0.13333…，newLow = 2.5/6 = 0.41666…
        assertThat(metrics.nullMean()).isCloseTo(2.0 / 15.0, within(0.02));
        assertThat(metrics.nullStd()).isGreaterThan(0.0);
        assertThat(metrics.anchorRatio()).isCloseTo(0.15 / (2.0 / 15.0), within(0.2));
    }

    @Test
    void ratioAndZAreInternallyConsistentWithTheNullMoments() {
        LearningMetrics metrics = calculator.calculate(mainPair());

        assertThat(metrics.anchorRatio())
                .isCloseTo(metrics.anchorRegret() / metrics.nullMean(), within(1e-12));
        assertThat(metrics.zEpisode())
                .isCloseTo((metrics.nullMean() - metrics.anchorRegret()) / metrics.nullStd(), within(1e-12));
        // 观测 regret 高于零分布均值 → z 为负（论文：负 z = 比自身随机排列更贵）
        assertThat(metrics.zEpisode()).isNegative();
    }

    @Test
    void sameSeedIsBitwiseReproducible() {
        LearningMetrics first = new LearningMetricsCalculator(20260101L, 400).calculate(mainPair());
        LearningMetrics second = new LearningMetricsCalculator(20260101L, 400).calculate(mainPair());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentSeedStillConvergesToTheSameAnalyticNull() {
        LearningMetrics other = new LearningMetricsCalculator(999L, 400).calculate(mainPair());

        assertThat(other.nullMean()).isCloseTo(2.0 / 15.0, within(0.02));
        assertThat(other.anchorRegret()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void degenerateNullYieldsNullRatioAndZ() {
        // 两笔都以成本底价成交 → π 全为 0 → 观测与零分布都为 0（退化）
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 50.0, 1));
        rows.add(agree("S1", "SKU1", 100.0, 50.0, 50.0, 2));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.anchorRegret()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.nullMean()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.nullStd()).isCloseTo(0.0, within(1e-9));
        assertThat(metrics.anchorRatio()).isNull();
        assertThat(metrics.zEpisode()).isNull();
        assertThat(metrics.newLowRatioZ()).isNull();
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— 5 个新测试中 `permutationNullConvergesToAnalyticMean`、`ratioAndZAreInternallyConsistentWithTheNullMoments`、`degenerateNullYieldsNullRatioAndZ` 因 `nullMean()`/`anchorRatio()`/`zEpisode()` 为 null 而失败（`sameSeedIsBitwiseReproducible` 与 `differentSeedStillConvergesToTheSameAnalyticNull` 也会因 null 而失败）

- [ ] **Step 3: 实现置换零分布**

把 `levelStatistic` 的签名与 `count == 0` 分支改为返回 `double` / `0.0`：

```java
    /** 跨 pair 汇总水平统计量：regret=true 取 max(0, π_t − m_t)，否则取 1[π_t < m_t − ε]；分母为 |R|。 */
    private static double levelStatistic(List<Pair> pairs, boolean regret) {
        double sum = 0.0;
        int count = 0;
        for (Pair pair : pairs) {
            double[] part = accumulate(pair.positions(), regret);
            sum += part[0];
            count += (int) part[1];
        }
        return count == 0 ? 0.0 : sum / count;
    }
```

在 `levelStatistic` 之后新增：

```java
    /**
     * 构造论文 §E.6 的置换零分布：B 次重复，每次对**每个 pair 独立**洗牌其位置序列后重算同一统计量。
     *
     * <p>单个共享 {@link Random}（固定种子）按 pair 字典序依次洗牌，因此结果逐位可复现。返回 [均值, 总体标准差]。
     */
    private double[] permutationNull(List<Pair> pairs, boolean regret) {
        Random random = new Random(seed);
        double[] values = new double[replicates];
        for (int b = 0; b < replicates; b++) {
            List<Pair> shuffled = new ArrayList<>(pairs.size());
            for (Pair pair : pairs) {
                shuffled.add(new Pair(pair.key(), shuffle(pair.positions(), random)));
            }
            values[b] = levelStatistic(shuffled, regret);
        }
        return moments(values);
    }
```

把 `calculate` 整体替换为：

```java
    /** 计算一个 episode 的全部 §E.6 指标；不适用者为 null。 */
    public LearningMetrics calculate(List<NegotiationRecordRow> rows) {
        List<Pair> pairs = honestRepeatPairs(rows);
        if (pairs.isEmpty()) {
            return new LearningMetrics(
                    null, null, null, null, null, null, null, null, null, null, 0, 0);
        }
        int reorders = pairs.stream().mapToInt(Pair::reorders).sum();
        double regret = levelStatistic(pairs, true);
        double newLow = levelStatistic(pairs, false);
        double[] nullRegret = permutationNull(pairs, true);
        double[] nullNewLow = permutationNull(pairs, false);
        Double ratio = nullRegret[0] == 0.0 ? null : regret / nullRegret[0];
        Double zEpisode = nullRegret[1] == 0.0 ? null : (nullRegret[0] - regret) / nullRegret[1];
        Double newLowZ = nullNewLow[1] == 0.0 ? null : (newLow - nullNewLow[0]) / nullNewLow[1];
        return new LearningMetrics(
                regret,
                newLow,
                ratio,
                zEpisode,
                newLowZ,
                nullRegret[0],
                nullRegret[1],
                null,
                null,
                null,
                pairs.size(),
                reorders);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（12 tests）

- [ ] **Step 5: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsCalculator.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsCalculatorTest.java
git commit -m "feat(evaluation): add permutation null, AnchorRatio and z to §E.6 metrics"
```

---

### Task 7: 三个半年比较（h_SE / b_SE / h_fraud）

**Files:**
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsCalculator.java`（`calculate` 的三个 null 占位换成实算，新增 5 个 private static 方法）
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsCalculatorTest.java`（追加 5 个测试）

**Interfaces:**
- Produces（全部 private，仅由 `calculate` 调用）:
  - `honestConcluded(List<NegotiationRecordRow>) → List<NegotiationRecordRow>`：`supplier_type == "good"` 且 `dayConcluded != null`，按结束日升序（稳定排序）
  - `surplusScore(NegotiationRecordRow) → double`：`s_z = 1[成交] · SE_z`，`SE_z = (参考价 − 成交价)/Δ` **不截断**，Δ ≤ 0 或价格缺失记 0
  - `surplusHalfLift(...) → Double`、`surplusTrend(...) → Double`、`fraudHalfLift(List<NegotiationRecordRow>) → Double`
  - `medianDay(List<NegotiationRecordRow>) → int`、`fraudDealShare(List<NegotiationRecordRow>) → double`

**手算依据**：
- `h_SE` fixture（4 个诚信已结束会话，各用不同 SKU 以免形成重复 pair）：日 1/2/3/4 的 `s_z` = 0.2 / 0.5 / 0（未成交）/ 0.8 → 早半 {1,2} 均值 0.35、晚半 {3,4} 均值 0.40 → `h_SE = 0.05`
- `b_SE` 同一 fixture：`Σ(d−d̄)(s−s̄) = 0.2625 − 0.0625 − 0.1875 + 0.6375 = 0.65`，`Σ(d−d̄)² = 5.0` → `b_SE = 0.13`
- 奇数 n fixture（5 个诚信会话，`s_z` = 0.2/0.4/0.6/0.8/1.0）：早半 = 前 ⌊5/2⌋ = 2 个（均值 0.3），晚半 = 后 3 个（均值 0.8）→ `h_SE = 0.5`
- `h_fraud` fixture（诚信 d1 成交、诚信 d2 未成交、欺诈 d3 成交、欺诈 d4 未成交）：中位日 = (2+3)/2 = 2 → 早半 {1,2} 的 β = 0/2 = 0，晚半 {3,4} 的 β = 1/2 = 0.5 → `h_fraud = −0.5`（晚半更多欺诈成交 = 变差）

- [ ] **Step 1: 追加失败测试**

在 `LearningMetricsCalculatorTest` 末尾追加：

```java
    /** 四个诚信已结束会话，各用不同 SKU（不形成重复 pair），s_z = 0.2 / 0.5 / 0 / 0.8。 */
    private static List<NegotiationRecordRow> halfYearFixture() {
        return new ArrayList<>(
                List.of(
                        agree("H1", "K1", 100.0, 50.0, 90.0, 1),
                        agree("H2", "K2", 100.0, 50.0, 75.0, 2),
                        disagree("H3", "K3", 100.0, 50.0, 3),
                        agree("H4", "K4", 100.0, 50.0, 60.0, 4)));
    }

    @Test
    void computesSurplusHalfLiftAndPerDayTrend() {
        LearningMetrics metrics = calculator.calculate(halfYearFixture());

        assertThat(metrics.seHalfLift()).isCloseTo(0.05, within(1e-9));
        assertThat(metrics.seTrend()).isCloseTo(0.13, within(1e-9));
        // 各 pair 只成交一次 → anchoring 部分不适用
        assertThat(metrics.repeatPairs()).isZero();
        assertThat(metrics.anchorRegret()).isNull();
    }

    @Test
    void oddSessionCountPutsTheExtraSessionLate() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("H1", "K1", 100.0, 50.0, 90.0, 1));
        rows.add(agree("H2", "K2", 100.0, 50.0, 80.0, 2));
        rows.add(agree("H3", "K3", 100.0, 50.0, 70.0, 3));
        rows.add(agree("H4", "K4", 100.0, 50.0, 60.0, 4));
        rows.add(agree("H5", "K5", 100.0, 50.0, 50.0, 5));

        LearningMetrics metrics = calculator.calculate(rows);

        // 早半 = 前 2 个（0.2, 0.4 → 0.3），晚半 = 后 3 个（0.6, 0.8, 1.0 → 0.8）
        assertThat(metrics.seHalfLift()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void fewerThanFourHonestSessionsYieldsNullHalfLift() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("H1", "K1", 100.0, 50.0, 90.0, 1));
        rows.add(agree("H2", "K2", 100.0, 50.0, 80.0, 2));
        rows.add(agree("H3", "K3", 100.0, 50.0, 70.0, 3));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.seHalfLift()).isNull();
        // 趋势不要求 n ≥ 4：三点最小二乘仍可拟合
        assertThat(metrics.seTrend()).isNotNull();
    }

    @Test
    void allSessionsOnOneDayYieldsZeroTrend() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("H1", "K1", 100.0, 50.0, 90.0, 7));
        rows.add(agree("H2", "K2", 100.0, 50.0, 75.0, 7));
        rows.add(disagree("H3", "K3", 100.0, 50.0, 7));
        rows.add(agree("H4", "K4", 100.0, 50.0, 60.0, 7));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.seTrend()).isCloseTo(0.0, within(1e-12));
        assertThat(metrics.seHalfLift()).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void fraudHalfLiftIsNegativeWhenLateHalfDealsMoreWithFraud() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("H1", "K1", 100.0, 50.0, 90.0, 1));
        rows.add(disagree("H2", "K2", 100.0, 50.0, 2));
        rows.add(row("B1", "K3", "bad", 100.0, 50.0, 90.0, "Agreement", 3));
        rows.add(row("B2", "K4", "bad", 100.0, 50.0, null, "Disagreement", 4));

        LearningMetrics metrics = calculator.calculate(rows);

        // 中位日 = (2+3)/2 = 2 → 早半 β=0，晚半 β=0.5 → h_fraud = −0.5
        assertThat(metrics.fraudHalfLift()).isCloseTo(-0.5, within(1e-9));
        // 诚信已结束会话只有 2 个 → h_SE 不适用
        assertThat(metrics.seHalfLift()).isNull();
    }

    @Test
    void fraudHalfLiftIsPositiveWhenFraudDealsConcentrateEarly() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(row("B1", "K1", "bad", 100.0, 50.0, 90.0, "Agreement", 1));
        rows.add(row("B2", "K2", "bad", 100.0, 50.0, null, "Disagreement", 2));
        rows.add(agree("H1", "K3", 100.0, 50.0, 90.0, 3));
        rows.add(disagree("H2", "K4", 100.0, 50.0, 4));

        LearningMetrics metrics = calculator.calculate(rows);

        assertThat(metrics.fraudHalfLift()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void allConcludedOnOneDayYieldsNullFraudHalfLift() {
        List<NegotiationRecordRow> rows = new ArrayList<>();
        rows.add(agree("H1", "K1", 100.0, 50.0, 90.0, 5));
        rows.add(agree("H2", "K2", 100.0, 50.0, 90.0, 5));
        rows.add(row("B1", "K3", "bad", 100.0, 50.0, 90.0, "Agreement", 5));
        rows.add(row("B2", "K4", "bad", 100.0, 50.0, 90.0, "Agreement", 5));

        assertThat(calculator.calculate(rows).fraudHalfLift()).isNull();
    }
```

说明：`allSessionsOnOneDayYieldsZeroTrend` 的四个会话虽然同日结束（故 `b_SE = 0`），但 `h_SE` 仍按列表序切分：`s_z` 依次为 0.2 / 0.5 / 0 / 0.8，早半均值 0.35、晚半均值 0.40 → `h_SE = +0.05`。这正好证明两项指标的分母不同（趋势看日期方差，半期提升看会话序）。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— 新增 7 个测试全部因 `seHalfLift()`/`seTrend()`/`fraudHalfLift()` 为 null 而失败

- [ ] **Step 3: 实现半年比较**

把 `calculate` 的返回语句中三个 `null, null, null`（对应 `seHalfLift`/`seTrend`/`fraudHalfLift`）换成实算，即在 `double[] nullNewLow = ...` 之后插入三行、并改返回：

```java
        List<NegotiationRecordRow> honest = honestConcluded(rows);
        Double halfLift = surplusHalfLift(honest);
        Double trend = surplusTrend(honest);
        Double fraudLift = fraudHalfLift(rows);
        return new LearningMetrics(
                regret,
                newLow,
                ratio,
                zEpisode,
                newLowZ,
                nullRegret[0],
                nullRegret[1],
                halfLift,
                trend,
                fraudLift,
                pairs.size(),
                reorders);
```

**注意**：半年比较不依赖重复 pair，因此当 `pairs.isEmpty()` 时也必须计算。把 `calculate` 开头的空 pair 早退分支改为：

```java
        List<Pair> pairs = honestRepeatPairs(rows);
        if (pairs.isEmpty()) {
            List<NegotiationRecordRow> honestOnly = honestConcluded(rows);
            return new LearningMetrics(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    surplusHalfLift(honestOnly),
                    surplusTrend(honestOnly),
                    fraudHalfLift(rows),
                    0,
                    0);
        }
```

（这也意味着 Task 5 的 `noRepeatPairYieldsAllNullLearningMetrics` 与 `emptyInputYieldsAllNullLearningMetrics` 两个测试中「`anchorRegret` 为 null」的断言仍然成立；若其中断言了 `seHalfLift` 为 null，需按新行为核对：`emptyInput` 时 `honestConcluded` 为空 → `seHalfLift` null、`seTrend` null、`fraudHalfLift` null，断言不变。）

在 `accumulate` 之后新增 5 个方法：

```java
    /** §E.6 的 Z_G：诚信且已结束的会话（成交与未成交都算），按结束日升序。 */
    private static List<NegotiationRecordRow> honestConcluded(List<NegotiationRecordRow> rows) {
        List<NegotiationRecordRow> out = new ArrayList<>();
        for (NegotiationRecordRow row : rows) {
            if ("good".equals(row.supplierType()) && row.dayConcluded() != null) {
                out.add(row);
            }
        }
        out.sort(Comparator.comparing(NegotiationRecordRow::dayConcluded));
        return out;
    }

    /** s_z = 1[成交] · SE_z；SE_z 不截断（与位置 π 不同），Δ ≤ 0 或价格缺失记 0。 */
    private static double surplusScore(NegotiationRecordRow row) {
        if (!row.isAgreement() || row.referencePrice() == null || row.costFloor() == null) {
            return 0.0;
        }
        double delta = row.referencePrice() - row.costFloor();
        return delta <= 0.0 ? 0.0 : (row.referencePrice() - row.finalPrice()) / delta;
    }

    /** h_SE = 晚半均值 − 早半均值；n &lt; 4 时不适用（论文 §E.6）。奇数 n 时多的一个归晚半。 */
    private static Double surplusHalfLift(List<NegotiationRecordRow> honest) {
        if (honest.size() < MIN_SESSIONS_FOR_HALF) {
            return null;
        }
        List<Double> scores = new ArrayList<>(honest.size());
        for (NegotiationRecordRow row : honest) {
            scores.add(surplusScore(row));
        }
        int middle = honest.size() / 2;
        return mean(scores.subList(middle, scores.size())) - mean(scores.subList(0, middle));
    }

    /** b_SE = s_z 对 d_z 的最小二乘斜率（在整个诚信集合上拟合）；全部同日 → 0；无会话 → null。 */
    private static Double surplusTrend(List<NegotiationRecordRow> honest) {
        if (honest.isEmpty()) {
            return null;
        }
        double meanDay = 0.0;
        double meanScore = 0.0;
        for (NegotiationRecordRow row : honest) {
            meanDay += row.dayConcluded();
            meanScore += surplusScore(row);
        }
        meanDay /= honest.size();
        meanScore /= honest.size();
        double numerator = 0.0;
        double denominator = 0.0;
        for (NegotiationRecordRow row : honest) {
            double dayDelta = row.dayConcluded() - meanDay;
            numerator += dayDelta * (surplusScore(row) - meanScore);
            denominator += dayDelta * dayDelta;
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    /**
     * h_fraud = β(早半) − β(晚半)，在**全部**已结束会话上按其中位日切分；β 为该半期内与欺诈方成交的会话占比。
     *
     * <p>正 = 改善（晚半更少与欺诈方成交）。样本 &lt; 4、或全部会话同日（切分后晚半为空）→ null。
     */
    private static Double fraudHalfLift(List<NegotiationRecordRow> rows) {
        List<NegotiationRecordRow> concluded = new ArrayList<>();
        for (NegotiationRecordRow row : rows) {
            if (row.dayConcluded() != null) {
                concluded.add(row);
            }
        }
        if (concluded.size() < MIN_SESSIONS_FOR_HALF) {
            return null;
        }
        concluded.sort(Comparator.comparing(NegotiationRecordRow::dayConcluded));
        int median = medianDay(concluded);
        List<NegotiationRecordRow> early = new ArrayList<>();
        List<NegotiationRecordRow> late = new ArrayList<>();
        for (NegotiationRecordRow row : concluded) {
            if (row.dayConcluded() <= median) {
                early.add(row);
            } else {
                late.add(row);
            }
        }
        if (early.isEmpty() || late.isEmpty()) {
            return null;
        }
        return fraudDealShare(early) - fraudDealShare(late);
    }

    /** 中位日：奇数取中间值，偶数取中间两值的整数均值（切分规则见 {@link #fraudHalfLift}）。 */
    private static int medianDay(List<NegotiationRecordRow> concluded) {
        int n = concluded.size();
        int mid = n / 2;
        return n % 2 == 1
                ? concluded.get(mid).dayConcluded()
                : (concluded.get(mid - 1).dayConcluded() + concluded.get(mid).dayConcluded()) / 2;
    }

    /** β(S) = S 中与欺诈供应商成交的会话数 / |S|。 */
    private static double fraudDealShare(List<NegotiationRecordRow> sessions) {
        long fraudDeals =
                sessions.stream()
                        .filter(row -> "bad".equals(row.supplierType()))
                        .filter(NegotiationRecordRow::isAgreement)
                        .count();
        return (double) fraudDeals / sessions.size();
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（19 tests）

- [ ] **Step 5: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsCalculator.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsCalculatorTest.java
git commit -m "feat(evaluation): add §E.6 half-year comparisons (h_SE, b_SE, h_fraud)"
```

---

### Task 8: 会话级聚合服务（含 Stouffer 合并）

**Files:**
- Create: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsService.java`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `RunLayoutResolver.RunLayout.negotiationRecordsJsonl(int)`、Task 2 的 `NegotiationRecordJsonlReader`、Task 5–7 的 `LearningMetricsCalculator` / `LearningMetrics`、Task 5 的 `SessionLearning`
- Produces:
  - `LearningMetricsService()` / `LearningMetricsService(NegotiationRecordJsonlReader reader, LearningMetricsCalculator calculator, RunLayoutResolver resolver)`
  - `forSession(Path sessionDir) → SessionLearning`（该会话无任何逐谈判记录文件 → **null**）
  - `forRun(Path sessionDir, int runIndex) → LearningMetrics`（无该文件 → **null**）
  - 聚合规则：`episodes` = 「有重复 pair 且 `zEpisode != null`」的 run 数（论文的 k）；`zAnchor = (1/√k) Σ z_r`，k = 0 → null；`anchorRatio` = 各 run 非 null 比率的均值（**不是**汇总均值之比）；其余指标取各 run 非 null 值均值；`repeatPairs`/`reorders` = 各 run 求和

- [ ] **Step 1: 写失败测试**

新建 `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsServiceTest.java`：

```java
package io.github.ecommercebench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ecommercebench.evaluation.io.NegotiationRecordJsonlReader;
import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.SessionLearning;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话级学习指标聚合测试：run 发现、Stouffer 合并、缺产物降级为 null。 */
class LearningMetricsServiceTest {

    /** 与 LearningMetricsCalculatorTest 主 fixture 相同的 jsonl 行（π = 0.8 / 0.6 / 0.9）。 */
    private static final String PAIR_LINES =
            "{\"supplier\":\"S1\",\"sku_id\":\"SKU1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":90.0,\"outcome\":\"Agreement\",\"day_concluded\":1}\n"
                    + "{\"supplier\":\"S1\",\"sku_id\":\"SKU1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":80.0,\"outcome\":\"Agreement\",\"day_concluded\":2}\n"
                    + "{\"supplier\":\"S1\",\"sku_id\":\"SKU1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                    + "\"cost_floor\":50.0,\"final_price\":95.0,\"outcome\":\"Agreement\",\"day_concluded\":3}\n";

    private final LearningMetricsService service = new LearningMetricsService();

    private static void writeRecords(Path session, int runIndex, String lines) throws Exception {
        Path metrics = session.resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(metrics.resolve("run_" + runIndex + "_negotiation_records.jsonl"), lines);
    }

    @Test
    void aggregatesTwoIdenticalRunsWithStoufferRule(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_m");
        writeRecords(session, 0, PAIR_LINES);
        writeRecords(session, 1, PAIR_LINES);

        SessionLearning learning = service.forSession(session);
        LearningMetrics single = new LearningMetricsCalculator().calculate(
                new NegotiationRecordJsonlReader()
                        .read(session.resolve("metrics/run_0_negotiation_records.jsonl")));

        assertThat(learning).isNotNull();
        assertThat(learning.episodes()).isEqualTo(2);
        assertThat(learning.repeatPairs()).isEqualTo(2);
        assertThat(learning.reorders()).isEqualTo(4);
        assertThat(learning.anchorRegret()).isCloseTo(0.15, within(1e-9));
        // 两个 run 完全相同 → 比率均值等于单 run 比率；z 按 (1/√k)Σz_r = z·√2
        assertThat(learning.anchorRatio()).isCloseTo(single.anchorRatio(), within(1e-9));
        assertThat(learning.zAnchor()).isCloseTo(single.zEpisode() * Math.sqrt(2.0), within(1e-9));
    }

    @Test
    void runsWithoutRepeatPairsDoNotCountTowardEpisodes(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_n");
        writeRecords(session, 0, PAIR_LINES);
        writeRecords(
                session,
                1,
                "{\"supplier\":\"S9\",\"sku_id\":\"K9\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"final_price\":90.0,\"outcome\":\"Agreement\","
                        + "\"day_concluded\":1}\n");

        SessionLearning learning = service.forSession(session);

        assertThat(learning.episodes()).isEqualTo(1);
        assertThat(learning.zAnchor())
                .isCloseTo(service.forRun(session, 0).zEpisode() / Math.sqrt(1.0), within(1e-9));
    }

    @Test
    void sessionWithoutArtifactYieldsNull(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_legacy");
        Files.createDirectories(session.resolve("metrics"));
        Files.writeString(session.resolve("metrics/run_0_analysis.json"), "{}");

        assertThat(service.forSession(session)).isNull();
        assertThat(service.forRun(session, 0)).isNull();
    }

    @Test
    void emptyArtifactYieldsZeroCountersAndNullValues(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_empty");
        writeRecords(session, 0, "");

        SessionLearning learning = service.forSession(session);

        assertThat(learning).isNotNull();
        assertThat(learning.episodes()).isZero();
        assertThat(learning.anchorRatio()).isNull();
        assertThat(learning.zAnchor()).isNull();
        assertThat(learning.repeatPairs()).isZero();
        assertThat(learning.reorders()).isZero();
    }

    @Test
    void aggregatesHalfYearMetricsAcrossRuns(@TempDir Path root) throws Exception {
        Path session = root.resolve("20260101_080000_h");
        String halfYear =
                "{\"supplier\":\"H1\",\"sku_id\":\"K1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"final_price\":90.0,\"outcome\":\"Agreement\",\"day_concluded\":1}\n"
                        + "{\"supplier\":\"H2\",\"sku_id\":\"K2\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"final_price\":75.0,\"outcome\":\"Agreement\",\"day_concluded\":2}\n"
                        + "{\"supplier\":\"H3\",\"sku_id\":\"K3\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"outcome\":\"Disagreement\",\"day_concluded\":3}\n"
                        + "{\"supplier\":\"H4\",\"sku_id\":\"K4\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"final_price\":60.0,\"outcome\":\"Agreement\",\"day_concluded\":4}\n";
        writeRecords(session, 0, halfYear);
        writeRecords(session, 1, halfYear);

        SessionLearning learning = service.forSession(session);

        assertThat(learning.seHalfLift()).isCloseTo(0.05, within(1e-9));
        assertThat(learning.seTrend()).isCloseTo(0.13, within(1e-9));
        assertThat(learning.episodes()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 LearningMetricsService`

- [ ] **Step 3: 实现服务**

新建 `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsService.java`：

```java
package io.github.ecommercebench.evaluation;

import io.github.ecommercebench.evaluation.io.NegotiationRecordJsonlReader;
import io.github.ecommercebench.evaluation.io.RunLayoutResolver;
import io.github.ecommercebench.evaluation.model.LearningMetrics;
import io.github.ecommercebench.evaluation.model.NegotiationRecordRow;
import io.github.ecommercebench.evaluation.model.SessionLearning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 会话级论文 §E.6 学习指标聚合器。
 *
 * <p>发现某会话 {@code metrics/} 下全部 {@code run_{i}_negotiation_records.jsonl}（legacy 平铺布局经
 * {@link RunLayoutResolver} 同样命中），按 run 序号升序逐个交给 {@link LearningMetricsCalculator}，再按论文口径聚合：
 * {@code anchorRatio} 取各 episode 比率的均值（不是汇总均值之比），{@code zAnchor} 用 Stouffer 法则 {@code (1/√k) Σ z_r}
 * 合并，{@code k}（即 {@code episodes}）为「有重复 pair 且零分布非退化」的 run 数。会话完全没有该产物时返回 null，
 * 以便调用方区分「旧产物」与「新产物但无重复采购」。
 */
public final class LearningMetricsService {

    private static final Pattern RECORDS_FILE = Pattern.compile("run_(\\d+)_negotiation_records\\.jsonl");

    private final NegotiationRecordJsonlReader reader;
    private final LearningMetricsCalculator calculator;
    private final RunLayoutResolver resolver;

    public LearningMetricsService() {
        this(new NegotiationRecordJsonlReader(), new LearningMetricsCalculator(), new RunLayoutResolver());
    }

    public LearningMetricsService(
            NegotiationRecordJsonlReader reader,
            LearningMetricsCalculator calculator,
            RunLayoutResolver resolver) {
        this.reader = reader;
        this.calculator = calculator;
        this.resolver = resolver;
    }

    /** 单个 run 的学习指标；该 run 无逐谈判记录文件时返回 null。 */
    public LearningMetrics forRun(Path sessionDir, int runIndex) {
        Path file = resolver.resolve(sessionDir).negotiationRecordsJsonl(runIndex);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return calculator.calculate(reader.read(file));
    }

    /** 会话级聚合；该会话没有任何逐谈判记录文件时返回 null。 */
    public SessionLearning forSession(Path sessionDir) {
        List<Path> files = recordFiles(sessionDir);
        if (files.isEmpty()) {
            return null;
        }
        List<LearningMetrics> runs = new ArrayList<>(files.size());
        for (Path file : files) {
            List<NegotiationRecordRow> rows = reader.read(file);
            runs.add(calculator.calculate(rows));
        }
        return aggregate(runs);
    }

    /** 按 run 序号升序列出该会话的逐谈判记录文件（organized 与 legacy 布局都覆盖）。 */
    private List<Path> recordFiles(Path sessionDir) {
        RunLayoutResolver.RunLayout layout = resolver.resolve(sessionDir);
        Path dir = layout.metricsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> RECORDS_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(path -> runIndexOf(path.getFileName().toString())))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("无法列出逐谈判记录: " + dir, exception);
        }
    }

    private static int runIndexOf(String fileName) {
        Matcher matcher = RECORDS_FILE.matcher(fileName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 论文口径的跨 episode 聚合；全 null 时各均值仍为 null，计数为求和值。 */
    private static SessionLearning aggregate(List<LearningMetrics> runs) {
        int episodes = 0;
        double zSum = 0.0;
        int repeatPairs = 0;
        int reorders = 0;
        for (LearningMetrics run : runs) {
            repeatPairs += run.repeatPairs();
            reorders += run.reorders();
            if (run.zEpisode() != null) {
                episodes++;
                zSum += run.zEpisode();
            }
        }
        return new SessionLearning(
                meanOf(runs, LearningMetrics::anchorRatio),
                meanOf(runs, LearningMetrics::anchorRegret),
                meanOf(runs, LearningMetrics::newLow),
                meanOf(runs, LearningMetrics::newLowRatioZ),
                episodes == 0 ? null : zSum / Math.sqrt(episodes),
                meanOf(runs, LearningMetrics::seHalfLift),
                meanOf(runs, LearningMetrics::seTrend),
                meanOf(runs, LearningMetrics::fraudHalfLift),
                episodes,
                repeatPairs,
                reorders);
    }

    /** 对非 null 值取均值；全为 null 时返回 null（与 RunComparator 跳过 null 的口径一致）。 */
    private static Double meanOf(
            List<LearningMetrics> runs, Function<LearningMetrics, Double> getter) {
        double sum = 0.0;
        int count = 0;
        for (LearningMetrics run : runs) {
            Double value = getter.apply(run);
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }
}
```

导入顺序已按字母序给出（`function` 在 `regex` 之前），与仓库既有文件一致。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=LearningMetricsServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 tests）

- [ ] **Step 5: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/LearningMetricsService.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/LearningMetricsServiceTest.java
git commit -m "feat(evaluation): aggregate §E.6 learning metrics per session"
```

---

### Task 9: 把学习指标挂上比较报告

**Files:**
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/ComparisonReport.java`
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RunComparator.java`
- Test: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/RunComparatorTest.java`

**Interfaces:**
- Consumes: Task 8 的 `LearningMetricsService.forSession(Path) → SessionLearning`
- Produces:
  - `ComparisonReport.SessionComparison(String name, int runCount, List<MetricStat> metrics, SessionLearning learning)` —— **保留** 3 参构造 `SessionComparison(String, int, List<MetricStat>)`（委托为 `learning = null`），使 `CrossModelProfilerTest`、`ProfileAxisTest` 等既有调用点零改动
  - `RunComparator()` / `RunComparator(AnalysisJsonReader, RunLayoutResolver)` / `RunComparator(AnalysisJsonReader, RunLayoutResolver, LearningMetricsService)`
  - `METRIC_DEFS` 保持 29 项不变（Python `compare_runs` 对齐）

- [ ] **Step 1: 追加失败测试**

在 `RunComparatorTest` 末尾（最后一个 `}` 之前）追加，并在文件顶部补 `import io.github.ecommercebench.evaluation.model.SessionLearning;`（若未使用则可省）：

```java
    private static void writeRecords(Path session, int index, String lines) throws Exception {
        Path metrics = session.resolve("metrics");
        Files.createDirectories(metrics);
        Files.writeString(metrics.resolve("run_" + index + "_negotiation_records.jsonl"), lines);
    }

    @Test
    void attachesSessionLearningWhenRecordsArtifactExists(@TempDir Path tempDir) throws Exception {
        Path session = tempDir.resolve("20260101_080000_learn");
        writeAnalysis(session, 0, "{\"profitability\":{\"final_balance\":100.0}}");
        writeRecords(
                session,
                0,
                "{\"supplier\":\"S1\",\"sku_id\":\"K1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"final_price\":90.0,\"outcome\":\"Agreement\",\"day_concluded\":1}\n"
                        + "{\"supplier\":\"S1\",\"sku_id\":\"K1\",\"supplier_type\":\"good\",\"reference_price\":100.0,"
                        + "\"cost_floor\":50.0,\"final_price\":80.0,\"outcome\":\"Agreement\",\"day_concluded\":2}\n");

        SessionComparison comparison = new RunComparator().compare(List.of(session)).sessions().get(0);

        assertThat(comparison.learning()).isNotNull();
        assertThat(comparison.learning().repeatPairs()).isEqualTo(1);
        assertThat(comparison.learning().reorders()).isEqualTo(1);
        // π = 0.8 → 0.6：regret 0、创新低 1
        assertThat(comparison.learning().anchorRegret()).isCloseTo(0.0, within(1e-9));
        assertThat(comparison.learning().newLow()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void legacySessionWithoutRecordsHasNullLearningAndUnchangedMetrics(@TempDir Path tempDir)
            throws Exception {
        Path session = tempDir.resolve("20260101_080000_old");
        writeAnalysis(session, 0, "{\"profitability\":{\"final_balance\":100.0}}");

        SessionComparison comparison = new RunComparator().compare(List.of(session)).sessions().get(0);

        assertThat(comparison.learning()).isNull();
        assertThat(comparison.metrics()).hasSize(RunComparator.METRIC_DEFS.size());
        assertThat(RunComparator.METRIC_DEFS).hasSize(29);
        assertThat(comparison.metric("final_balance").mean()).isCloseTo(100.0, within(1e-9));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=RunComparatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 方法 learning()`

- [ ] **Step 3: 扩展 SessionComparison**

把 `ComparisonReport.java` 的 `SessionComparison` 整体替换为：

```java
    /**
     * 单个会话的聚合：名称、run 数、各指标统计与论文 §E.6 学习指标。
     *
     * <p>{@code learning} 为 null 表示该会话没有逐谈判记录产物（旧产物）或无法定位；此时学习轴在雷达上留空。保留三参构造以
     * 兼容既有调用点（等价于 {@code learning = null}）。
     */
    public record SessionComparison(
            String name, int runCount, List<MetricStat> metrics, SessionLearning learning) {

        public SessionComparison {
            metrics = List.copyOf(metrics);
        }

        public SessionComparison(String name, int runCount, List<MetricStat> metrics) {
            this(name, runCount, metrics, null);
        }

        public MetricStat metric(String label) {
            return metrics.stream().filter(m -> m.label().equals(label)).findFirst().orElse(null);
        }
    }
```

文件顶部补 `import io.github.ecommercebench.evaluation.model.SessionLearning;`？—— **不需要**：`SessionLearning` 与 `ComparisonReport` 同在 `...evaluation.model` 包内，同包类无需导入。

- [ ] **Step 4: 让 RunComparator 填充 learning**

`RunComparator.java` 的四处改动：

导入区补一行（放在既有 `io.github.ecommercebench.evaluation.io.RunLayoutResolver` 之后）：

```java
import io.github.ecommercebench.evaluation.model.SessionLearning;
```

字段与构造器替换为：

```java
    private final AnalysisJsonReader reader;
    private final RunLayoutResolver resolver;
    private final LearningMetricsService learning;

    public RunComparator() {
        this(new AnalysisJsonReader(), new RunLayoutResolver(), new LearningMetricsService());
    }

    public RunComparator(AnalysisJsonReader reader, RunLayoutResolver resolver) {
        this(reader, resolver, new LearningMetricsService());
    }

    public RunComparator(
            AnalysisJsonReader reader, RunLayoutResolver resolver, LearningMetricsService learning) {
        this.reader = reader;
        this.resolver = resolver;
        this.learning = learning;
    }
```

`compare` 的收尾行替换为：

```java
            SessionLearning sessionLearning = learning.forSession(dir);
            sessions.add(
                    new SessionComparison(
                            dir.getFileName().toString(), reports.size(), metrics, sessionLearning));
```

类级 Javadoc 追加一句：

```
 * <p>另外按会话读取逐谈判记录（{@code metrics/run_{i}_negotiation_records.jsonl}），把论文 §E.6 的学习维度指标挂在
 * {@link SessionComparison#learning()} 上；旧产物没有该文件时为 null。{@link #METRIC_DEFS} 保持不变以与 Python
 * {@code compare_runs} 的比较表形状对齐。
```

- [ ] **Step 5: 运行 evaluation 模块全部测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test`
Expected: PASS，0 failures、0 errors（含既有 `CrossModelProfilerTest`、`RankingTableTest`、`ChatboxExtractorTest` 等）

- [ ] **Step 6: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/model/ComparisonReport.java \
        ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RunComparator.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/RunComparatorTest.java
git commit -m "feat(evaluation): attach §E.6 session learning to comparison report"
```

---

### Task 10: 雷达学习轴切到 AnchorRatio

**Files:**
- Modify: `ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/ProfileAxis.java`（`LEARNING` 常量、`value()` 的 `case LEARNING`、类级 Javadoc）
- Modify: `ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/ProfileAxisTest.java`

**Interfaces:**
- Consumes: Task 9 的 `SessionComparison.learning()`、Task 5 的 `SessionLearning.anchorRatio()`
- Produces: `ProfileAxis.LEARNING.label() == "Learning (AnchorRatio)"`、`ProfileAxis.LEARNING.lowerIsBetter() == true`、`value(session)` = `session.learning() == null ? null : session.learning().anchorRatio()`

**说明**：`ProfileAxisTest` 的类级 Javadoc 现在已写着「欺诈/偿付/执行/**学习**为越低越好」，而断言却是 `isFalse()` —— 本次改动让代码与那句 Javadoc 首次一致。

- [ ] **Step 1: 改测试（先红）**

`ProfileAxisTest.java` 三处改动：

① 顶部导入区补：

```java
import io.github.ecommercebench.evaluation.model.SessionLearning;
```

② `directionsMatchPaperSignFlipSet` 中把 LEARNING 的断言从该方法的「false 组」移到「true 组」：

```java
    @Test
    void directionsMatchPaperSignFlipSet() {
        assertThat(ProfileAxis.PRIMARY.lowerIsBetter()).isFalse();
        assertThat(ProfileAxis.NEGOTIATION.lowerIsBetter()).isFalse();
        assertThat(ProfileAxis.EFFICIENCY.lowerIsBetter()).isFalse();
        assertThat(ProfileAxis.FRAUD.lowerIsBetter()).isTrue();
        assertThat(ProfileAxis.SOLVENCY.lowerIsBetter()).isTrue();
        assertThat(ProfileAxis.EXECUTION.lowerIsBetter()).isTrue();
        assertThat(ProfileAxis.LEARNING.lowerIsBetter()).isTrue();
    }
```

③ `computesAxisValuesIncludingDerivedRatios` 中删去这一行（`fullSession()` 无 `learning`，学习轴现为 null）：

```java
        assertThat(ProfileAxis.LEARNING.value(session)).isCloseTo(0.05, within(1e-9));
```

并在该类末尾（最后一个 `}` 之前）追加两个测试与一个助手：

```java
    private static SessionLearning learning(Double anchorRatio) {
        return new SessionLearning(
                anchorRatio, 0.15, 0.5, null, -0.5, 0.05, 0.13, -0.5, 1, 1, 2);
    }

    @Test
    void learningAxisReadsAnchorRatioFromSessionLearning() {
        SessionComparison session =
                new SessionComparison("modelL", 2, List.of(), learning(1.35));

        assertThat(ProfileAxis.LEARNING.value(session)).isCloseTo(1.35, within(1e-9));
        assertThat(ProfileAxis.LEARNING.label()).isEqualTo("Learning (AnchorRatio)");
    }

    @Test
    void learningAxisIsNullWithoutSessionLearning() {
        assertThat(ProfileAxis.LEARNING.value(fullSession())).isNull();
    }

    @Test
    void learningAxisIsNullWhenAnchorRatioIsNotApplicable() {
        SessionComparison session =
                new SessionComparison("modelN", 1, List.of(), learning(null));

        assertThat(ProfileAxis.LEARNING.value(session)).isNull();
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ecbench-evaluation -am test -Dtest=ProfileAxisTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— `directionsMatchPaperSignFlipSet`（期望 true 实为 false）、`learningAxisReadsAnchorRatioFromSessionLearning`（期望 1.35 实为 null，且 label 仍是旧值）

- [ ] **Step 3: 改 ProfileAxis**

`ProfileAxis.java` 三处改动：

① 常量：

```java
    LEARNING("learning", "Learning (AnchorRatio)", true);
```

② `value()` 的分支：

```java
            case LEARNING -> session.learning() == null ? null : session.learning().anchorRatio();
```

③ 类级 Javadoc 替换为：

```java
/**
 * 论文 §3.7 / Figure 2 的七个评估轴（主要得分 + 顺时针 6 维），按固定顺序排列。
 *
 * <p>每轴从会话聚合的 {@link MetricStat} 均值取原始值，学习轴例外：它读 {@link SessionComparison#learning()} 的
 * {@code anchorRatio}（论文 §E.6 的 AnchorRatio，来自逐谈判记录重算），因此旧产物没有该记录时学习轴为 null。
 * 欺诈规避、偿付能力、运营执行与学习为「越低越好」（归一化时翻转）；派生的偿付/效率轴在分母为 0 或缺样本时返回 {@code null}。
 */
```

（Javadoc 中的 `{@link SessionComparison#learning()}` 需要该类型可见：文件已导入 `ComparisonReport.SessionComparison`，无需新增导入。）

- [ ] **Step 4: 运行 evaluation 模块全部测试确认通过**

Run: `mvn -q -pl ecbench-evaluation -am test`
Expected: PASS，0 failures、0 errors

- [ ] **Step 5: 运行 app 模块全部测试确认未回归**

Run: `mvn -q -pl ecbench-app -am test`
Expected: PASS，0 failures、0 errors。`EvaluationCommandProfileTest` 不需要改：它只断言「PNG 存在且非空」与 CSV 表头，而 `RadarPlotter` 把 NaN 轴回退为 0.0。

- [ ] **Step 6: 提交**

```bash
git add ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/ProfileAxis.java \
        ecbench-evaluation/src/test/java/io/github/ecommercebench/evaluation/ProfileAxisTest.java
git commit -m "feat(evaluation)!: rank learning axis by paper AnchorRatio instead of se_half_lift proxy

BREAKING CHANGE: 旧产物（无 negotiation_records.jsonl）的雷达学习轴变为空。
这是 spec §4.3 记录的有意变更。"
```

---

### Task 11: 门禁验证与文档

**Files:**
- Modify: `README.md`（运行产物小节、评估子命令小节、Web 看板小节、测试与质量门小节）
- Modify（可选，独立提交）: `ecbench-simulation/src/main/java/io/github/ecommercebench/simulation/SimulationEngine.java:491-494`

**Interfaces:** 无（本任务不产生供他人调用的接口）

**实测到的门禁基线（2026-10-12，本计划开工前）**：
- `mvn -o -pl ecbench-evaluation spotless:check` → **BUILD FAILURE**，「Violations also present in 22 other files」（共 23 个文件）。根因：根 pom 的 `<googleJavaFormat/>` 未指定 `<style>AOSP</style>`，默认 GOOGLE 2 空格，而 evaluation/app/opponent 等模块既有代码是 4 空格。**这是先于本特性存在的红基线**，不是本次引入。
- `mvn -o checkstyle:check`（根，全模块）→ **BUILD FAILURE**，唯一违规：`ecbench-simulation/.../SimulationEngine.java:[494] LineLength: 124 个字符，最多 120`。同样是先存在的。
- `mvn -o -pl ecbench-evaluation checkstyle:check` → **BUILD SUCCESS**（0 violations）。
- 结论：**`mvn clean verify` 在本仓库当前无法全绿**（spotless 绑在 verify 阶段）。本任务用「全模块 test + 所改模块 checkstyle + 重打 fat jar」作为真实门禁，并把 spotless 基线如实写进收尾报告。

- [ ] **Step 1: 跑全模块测试**

Run: `mvn -o clean test`
Expected: BUILD SUCCESS，全模块 0 failures、0 errors（既有 skip 保持）

- [ ] **Step 2: 跑所改模块的 Checkstyle**

Run: `mvn -o -q -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: BUILD SUCCESS，两处均输出 `You have 0 Checkstyle violations.`

- [ ] **Step 3: 重打 fat jar 并冒烟 serve**

```bash
mvn -o -q -DskipTests clean package
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir log --port 8099 &
sleep 3
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8099/api/sessions
curl -s "http://127.0.0.1:8099/api/compare" | head -c 200
kill %1
```

Expected: `200`，且 `/api/compare` 的 `sessions`/`radar`/`ranking` 三段与改动前一致（现有 4 个会话都没有 `negotiation_records.jsonl`，故雷达学习轴为空、页面不报错）。

- [ ] **Step 4: 更新 README 的运行产物小节**

在 `README.md` 的这一行之后：

```markdown
- `metrics/run_<i>_negotiation_metrics.json`、`run_<i>_analysis.json`
```

插入一行：

```markdown
- `metrics/run_<i>_negotiation_records.jsonl`（**Java 侧附加产物**：逐谈判原始记录，论文 §E.6 学习维度指标的重算输入；Python 参考实现不产出该文件，缺失时学习维度显示为不适用）
```

- [ ] **Step 5: 更新 README 的评估子命令与 Web 看板小节**

① 在 `evaluate compare` 的代码块之后（`### 本地 Web 看板（serve）` 之前）插入：

```markdown
`evaluate profile` 的七轴画像中，学习轴口径为论文 §E.6 的 **AnchorRatio**（越低越好，1.0 表示与自身价格的随机排列无异），
由 `metrics/run_<i>_negotiation_records.jsonl` 重算得出；早于该产物的旧会话在此轴上留空。
```

② 把 Web 看板小节里这句（已与实现不符）：

```markdown
数值口径完全复用 `evaluate` 子命令的同一套实现（`RunComparator` / `CrossModelProfiler` / `RankingTable`），图表由前端 Chart.js 渲染。
```

替换为：

```markdown
数值口径完全复用 `evaluate` 子命令的同一套实现（`RunComparator` / `CrossModelProfiler` / `RankingTable`）；
前端为零依赖单页应用，图表以纯 SVG 渲染，不引用任何外部 CDN 资源。
```

- [ ] **Step 6: 更新 README 的测试与质量门小节（如实记录基线）**

把该小节的命令与三条要点替换为：

```markdown
```bash
mvn clean test                                  # 全模块测试
mvn -pl ecbench-evaluation,ecbench-app checkstyle:check   # 所改模块风格门
mvn -DskipTests clean package                   # 重打 fat jar
```

- 全 7 模块共 **56+ 个测试类、200+ 个测试方法**，含离线 E2E（`BenchmarkEndToEndTest`）与 Python↔Java 双向兼容测试（`PythonCompatibilityTest`）。
- **Checkstyle**（行宽 120、禁 star import、方法 ≤ 150 行）：所改模块须 0 违规。
- **JaCoCo** 行覆盖率门：`domain` / `simulation` / `opponent` 均须 ≥ 85%，未达标即 `BUILD FAILURE`。
- **Spotless 已知红基线**：根 pom 的 `<googleJavaFormat/>` 未设 `<style>AOSP</style>`，默认 2 空格与仓库既有 4 空格模块冲突，
  `spotless:check` 在 `evaluation` 模块即报 23 个文件违规，因而 `mvn clean verify` 无法全绿。**不要**用 `mvn spotless:apply` "修复"——
  它会重排整模块既有文件、产生巨量无关 diff。修法是把根 pom 改为 `<googleJavaFormat><style>AOSP</style></googleJavaFormat>`，属独立任务。
```

- [ ] **Step 7:（可选，独立提交）修掉唯一的 Checkstyle 存量违规**

`ecbench-simulation/.../SimulationEngine.java:491-494` 现为 124 字符超限。按同文件 L495-500 的既有换行风格改为：

```java
                        .filter(
                                supplier ->
                                        storeType == null
                                                || supplier.categoriesServed().stream()
                                                        .anyMatch(storeCategories::contains))
```

纯格式换行，无行为变更（`ecbench-simulation` 的 JaCoCo ≥85% 门不受影响）。改完运行：

```bash
mvn -o -q -pl ecbench-simulation checkstyle:check && mvn -o -q -pl ecbench-simulation test
```

Expected: 均 BUILD SUCCESS（0 violations、0 failures）。随后独立提交：

```bash
git add ecbench-simulation/src/main/java/io/github/ecommercebench/simulation/SimulationEngine.java
git commit -m "style(simulation): wrap 124-char line to satisfy checkstyle (pre-existing violation)"
```

若不希望在本特性中触碰 `ecbench-simulation`，跳过本步，并在收尾报告中如实说明「根级 checkstyle 有 1 处存量违规（SimulationEngine.java:494），非本次引入」。

- [ ] **Step 8: 提交文档**

```bash
git add README.md
git commit -m "docs: record negotiation_records artifact, AnchorRatio axis and spotless baseline"
```

- [ ] **Step 9: 收尾自查（逐条核对后在提交信息或报告中给出证据）**

- `git log --oneline` 显示 Task 1–11 各自的小步提交
- `mvn -o clean test` 全绿；`mvn -o -pl ecbench-evaluation,ecbench-app checkstyle:check` 0 violations
- `git diff --stat d85d791..HEAD -- ecbench-opponent ecbench-domain ecbench-simulation ecbench-agent ecbench-llm` 为空（Step 7 被执行时 `ecbench-simulation` 仅有一处换行 diff）
- `ecbench-app/.../MetricsJsonWriter.java` 未被修改（`git diff --stat d85d791..HEAD -- '*MetricsJsonWriter.java'` 为空）
- 现有 `log/` 四个会话的 `analysis.json` / `negotiation_metrics.json` 内容与开工前逐字一致（未重跑基准，不应有任何变化）

---

## 阶段 A 完成判据

1. 一次新 run 后 `log/<session>/metrics/run_0_negotiation_records.jsonl` 存在且字段齐备（Task 3/4 的 round-trip 与收尾断言覆盖）。
2. `LearningMetricsCalculatorTest` 19 个测试全绿，含手算值（AnchorRegret=0.15、NewLow=0.5、h_SE=0.05、b_SE=0.13、h_fraud=±0.5）与固定种子逐位可复现断言。
3. 旧产物零回归：`RunComparatorTest.legacySessionWithoutRecordsHasNullLearningAndUnchangedMetrics` 断言 `METRIC_DEFS` 仍为 29 项、`learning == null`。
4. 雷达学习轴口径为 AnchorRatio 且方向为「越低越好」（`ProfileAxisTest`）。
5. README 记录新产物、口径变更与 spotless 红基线。

阶段 A 完成后进入 **阶段 B 计划**：`docs/superpowers/plans/2026-10-12-dashboard-paper-views.md`（六维度视图 / rank crossings / 三类诊断图 / 浅色主题 / 全站中文 / chatbox 欺诈标注）。
