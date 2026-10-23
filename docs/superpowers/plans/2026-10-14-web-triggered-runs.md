# Web 触发运行 + 默认以 Web 服务启动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让浏览器能提交 / 查询 / 取消一次基准运行，并把 `java -jar ecbench-app.jar`（无参数）的默认行为从「报错退出」改为「起 Web 服务」。

**Architecture:** 新增 `app.job` 子包承载任务状态机（内存注册表 + 单线程串行执行器 + 可取消），通过 `RunLauncher` 函数式接缝与真实运行解耦；每次 launch 新建一个 `RunComponentFactory` 以规避其「一次配置、终身缓存」的实例字段串味。HTTP 层继续用 JDK `HttpServer`，只把 `ApiRouter` 的「非 GET → 405」改成按 (方法, 路径) 分发，并新增 `ApiException` 承载 403/409/413。密钥来自「进程 env + 仓库根 `.env`」，由 `EffectiveEnvironment` 统一供给，`ModelKeyGuard` 是就绪判定的唯一实现点。

**Tech Stack:** Java 17、Spring Boot 3.5.16（`WebApplicationType.NONE`，不变）、Picocli 4.7.7、JDK `com.sun.net.httpserver`、Jackson、JUnit 5 + AssertJ、零依赖原生 JS 前端。

**Spec:** `docs/superpowers/specs/2026-10-14-web-triggered-runs-design.md`（计划从 spec 论证，执行者需同时读两份）

## Global Constraints

- **主门禁：`mvn -o clean test`** —— 7 模块 BUILD SUCCESS；基线 `18+45+32+10+60+84+105 = 354 tests, 0 failures, 1 skipped`（2026-10-14 实测），只增不减。
- **风格门禁：`mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`** —— 0 违规。改了 `ecbench-llm` 时**不要**对它跑 checkstyle（存量 4 处红，非本次引入）。
- **禁用 `mvn clean verify`**：Spotless 绑在 verify 且根 pom `<googleJavaFormat/>` 未设 `<style>AOSP</style>`，必然在 evaluation 模块报 20+ 违规。**禁用 `mvn spotless:apply`**：会重排整模块产生巨量无关 diff。
- 缩进：`ecbench-app` / `ecbench-evaluation` / `ecbench-llm` 用 **4 空格**；`ecbench-domain` 用 2 空格；`resources/web` 前端三件套用 **2 空格**。新增文件镜像所在模块。
- Checkstyle 生效规则：`AvoidStarImport`、`FileTabCharacter`、`LineLength(≤120)`、命名类、`MethodLength(≤150)`。无 `UnusedImports` → 多余导入不会被拦，需自查。中文行按**字符数**量宽，不要用 `wc`/`awk`（按字节会虚高约 3 倍）。
- `RunComparator.METRIC_DEFS` 必须仍是 29 行（与 Python `compare_runs` 对齐的冻结契约）。
- 不新增任何 Maven 依赖。不引入前端框架 / 构建步骤 / 外部图表库。
- 提交纪律：**只 `git add` 本任务明确涉及的文件，绝不 `git add -A`**。工作树里 `ecbench-domain` / `ecbench-simulation` 有 18 个未提交的格式化改动，属他人存量，全程不得触碰、不得提交。
- 每个任务末尾的 `git commit` 步骤，执行前需得到用户明确同意（平台规则：未经要求不提交）。
- 冒烟核对状态码用 `curl -sS -D - -o <body文件>` **单条**执行；清理后台进程用 `kill <PID>`，**不用 `pkill -f`**（会匹配执行它的 shell 自身）。

## File Structure

**新建（主代码 12 个）**

| 文件 | 责任 |
|---|---|
| `ecbench-app/.../app/config/RunOptions.java` | 13 个只读访问器接口，CLI 与 Web 两条提交路径的共同契约 |
| `ecbench-app/.../app/config/SecretsFileLoader.java` | 读 `.env`，永不抛异常 |
| `ecbench-app/.../app/config/EffectiveEnvironment.java` | env 覆盖 `.env` 的唯一密钥视图 |
| `ecbench-app/.../app/config/LlmClientProviders.java` | provider 构造的唯一静态工厂 |
| `ecbench-app/.../app/job/JobState.java` | 五态枚举 + `terminal()` |
| `ecbench-app/.../app/job/Job.java` | 单个任务的可变状态载体 |
| `ecbench-app/.../app/job/JobRequest.java` | record，`implements RunOptions`，含 `parse` |
| `ecbench-app/.../app/job/RunLauncher.java` | 函数式接缝 |
| `ecbench-app/.../app/job/BenchmarkRunLauncher.java` | 生产实现，每 job 新建 factory |
| `ecbench-app/.../app/job/ModelKeyGuard.java` | 密钥就绪判定唯一实现点 |
| `ecbench-app/.../app/job/JobService.java` | 注册表 + 串行执行器 + 状态机 + JobView 序列化 |
| `ecbench-app/.../app/web/ApiException.java` | 携带状态码的 RuntimeException |
| `ecbench-app/.../app/web/ModelCatalogService.java` | `GET /api/models` 的 JSON 产出 |
| `ecbench-app/.../app/cli/ServeOptions.java` | Picocli mixin：`--port`/`--host`/`--catalog` |
| `ecbench-app/.../app/web/DashboardLauncher.java` | 起服务的共用编排 + 路径解析静态助手 |

**修改（主代码 8 个）**：`CliRunOptions`、`ConfigurationMerger`、`ApplicationWiring`、`RunComponentFactory`、`ApiRouter`、`DashboardServer`、`ServeCommand`、`BenchmarkCommand`、`EcommerceBenchApplication`、`ecbench-llm/.../ProviderResolver`。

**新建（测试 7 个）**：`SecretsFileLoaderTest`、`EffectiveEnvironmentTest`、`LlmClientProvidersTest`、`RunComponentFactoryTest`、`JobRequestTest`、`ModelKeyGuardTest`、`JobServiceTest`。
**修改（测试 3 个）**：`ConfigurationMergerTest`、`DashboardServerTest`、`BenchmarkCommandTest`。
**改名（测试 1 个）**：`ServeCommandCatalogTest` → `DashboardLauncherPathTest`。
**前端**：`index.html` / `app.js` / `app.css` 各增两块 + `boot()` 重构 + 新增 `apiSend()`。
**文档**：`README.md`、`.gitignore`。

---

### Task 1: `RunOptions` 接口与 `ConfigurationMerger` 类型放宽

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/config/RunOptions.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/CliRunOptions.java:13`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/config/ConfigurationMerger.java:40`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/config/ConfigurationMergerTest.java`

**Interfaces:**
- Consumes: `CliRunOptions` 现有的 13 个无参访问器（`model()` … `dataDir()`），已存在，无需新增。
- Produces: `interface RunOptions`，13 个方法签名如 Step 1 所示；`ConfigurationMerger.merge(RunOptions cli, Map<String,String> env, Path workingDir)`。后续 Task 5 的 `JobRequest` 与 Task 7 的 `JobService` 依赖这两个签名。

- [ ] **Step 1: 写失败测试**

在 `ConfigurationMergerTest` 末尾（最后一个 `}` 之前）追加。它用一个**非 `CliRunOptions`** 的 `RunOptions` 实现调 `merge`，只有形参真的放宽了才编译得过：

```java
    /** 证明 merge 接受任意 RunOptions 实现，而不只是 Picocli 注入的 CliRunOptions。 */
    @Test
    void acceptsAnyRunOptionsImplementation() throws Exception {
        Path wd = workdirWithConfig();
        RunOptions web = new StubRunOptions("gpt");

        BenchmarkOptions options = new ConfigurationMerger().merge(web, Map.of(), wd);

        assertThat(options.runConfig().modelKey()).isEqualTo("gpt");
        assertThat(options.runConfig().maxDays()).isEqualTo(365);
        assertThat(options.runConfig().seed()).isEqualTo(42L);
    }

    /** 模拟 Web 提交路径的最小 RunOptions：只给 model，其余取 CLI 同值默认。 */
    private record StubRunOptions(String model) implements RunOptions {
        @Override public int maxTokens() { return 16384; }
        @Override public int maxTurns() { return 4000; }
        @Override public int maxDays() { return 365; }
        @Override public double initialBalance() { return 100000.0; }
        @Override public double dailyFee() { return 50.0; }
        @Override public int maxTokenCapacity() { return 128000; }
        @Override public String tokenizerPath() { return null; }
        @Override public String logDir() { return null; }
        @Override public String jobFile() { return null; }
        @Override public int runs() { return 1; }
        @Override public Long seed() { return null; }
        @Override public String dataDir() { return null; }
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=ConfigurationMergerTest`
Expected: **编译失败** —— `RunOptions` 不存在（`cannot find symbol`）。

- [ ] **Step 3: 新建 `RunOptions`**

```java
package io.github.ecommercebench.app.config;

/**
 * 一次基准运行所需的全部只读参数，是 CLI 与 Web 两条提交路径的共同契约。
 *
 * <p>方法名与 {@code CliRunOptions} 的访问器逐一同名同签名，因此 Picocli 注入的 CLI 参数对象与
 * Web 侧的 {@code JobRequest} record 都能直接实现本接口，{@link ConfigurationMerger} 得以对两者一视同仁。
 */
public interface RunOptions {

    /** models_config.json 中的模型键。 */
    String model();

    /** 单次 LLM 调用的最大 token 数。 */
    int maxTokens();

    /** 单次 episode 的最大 agent 轮数。 */
    int maxTurns();

    /** 最大模拟天数。 */
    int maxDays();

    /** 初始银行余额。 */
    double initialBalance();

    /** 每日店铺运营费。 */
    double dailyFee();

    /** 上下文窗口 token 容量。 */
    int maxTokenCapacity();

    /** HuggingFace tokenizer 路径覆盖；null 表示走资源根默认。 */
    String tokenizerPath();

    /** 日志输出目录覆盖；null 表示走 {@code ./log}。 */
    String logDir();

    /** 预构建 job JSONL 路径覆盖；null 表示用内置 defaultJob。 */
    String jobFile();

    /** 并行运行次数。 */
    int runs();

    /** 确定性随机种子；null 表示由合并层回退到 42。 */
    Long seed();

    /** 数据目录覆盖；null 表示走资源根 {@code data/}。 */
    String dataDir();
}
```

- [ ] **Step 4: 让 `CliRunOptions` 实现它**

把类声明行

```java
public final class CliRunOptions {
```

改为

```java
public final class CliRunOptions implements RunOptions {
```

并在文件顶部 import 区加一行（该文件已 import `picocli.CommandLine.Option`）：

```java
import io.github.ecommercebench.app.config.RunOptions;
```

**不要新增或改名任何方法** —— 13 个访问器已存在且签名完全匹配。

- [ ] **Step 5: 放宽 `ConfigurationMerger.merge` 的形参类型**

把

```java
    public BenchmarkOptions merge(CliRunOptions cli, Map<String, String> env, Path workingDir) {
```

改为

```java
    public BenchmarkOptions merge(RunOptions cli, Map<String, String> env, Path workingDir) {
```

方法体**一字不改**。同时删掉文件顶部已不再使用的 `import io.github.ecommercebench.app.cli.CliRunOptions;`（Checkstyle 不拦未用导入，靠这一步自查）。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=ConfigurationMergerTest`
Expected: PASS，5 tests（原 4 + 新 1）。

- [ ] **Step 7: 跑全量门禁确认零回归**

Run: `mvn -o clean test`
Expected: BUILD SUCCESS，`ecbench-app` 106 tests（105 + 1），0 failures，1 skipped。

Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: 0 违规。

- [ ] **Step 8: Commit**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/config/RunOptions.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/config/ConfigurationMerger.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/cli/CliRunOptions.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/config/ConfigurationMergerTest.java
git commit -m "refactor(app): widen ConfigurationMerger to a RunOptions contract"
```

---

### Task 2: `SecretsFileLoader` 与 `EffectiveEnvironment`

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/config/SecretsFileLoader.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/config/EffectiveEnvironment.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/config/SecretsFileLoaderTest.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/config/EffectiveEnvironmentTest.java`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: 无（本任务是叶子）。
- Produces: `SecretsFileLoader.read(Path) -> Map<String,String>`（永不抛异常）；`EffectiveEnvironment.asMap() -> Map<String,String>`、包级构造 `EffectiveEnvironment(Map<String,String> processEnv, Map<String,String> fileEnv)`、静态 `EffectiveEnvironment.load() -> EffectiveEnvironment`、静态 `EffectiveEnvironment.defaultFile(Path workingDir) -> Path`。Task 3 / 6 / 14 依赖这些签名。

- [ ] **Step 1: 写 `SecretsFileLoaderTest`**

```java
package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** .env 解析契约：只取 KEY=VALUE，容忍缺文件/空文件/畸形行，且永不抛异常。 */
class SecretsFileLoaderTest {

    private final SecretsFileLoader loader = new SecretsFileLoader();

    @Test
    void missingFileYieldsEmptyMap(@TempDir Path dir) {
        assertThat(loader.read(dir.resolve("absent.env"))).isEmpty();
    }

    @Test
    void parsesPairsAndIgnoresCommentsAndBlanks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "# 注释行\n\nOPENAI_API_KEY=sk-abc\n  \nDASHSCOPE_API_KEY=sk-def\n");

        Map<String, String> parsed = loader.read(file);

        assertThat(parsed).containsOnlyKeys("OPENAI_API_KEY", "DASHSCOPE_API_KEY");
        assertThat(parsed.get("OPENAI_API_KEY")).isEqualTo("sk-abc");
    }

    @Test
    void stripsMatchingSurroundingQuotes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "A=\"quoted\"\nB='single'\nC=\"mismatched'\n");

        Map<String, String> parsed = loader.read(file);

        assertThat(parsed.get("A")).isEqualTo("quoted");
        assertThat(parsed.get("B")).isEqualTo("single");
        assertThat(parsed.get("C")).isEqualTo("\"mismatched'");
    }

    @Test
    void skipsMalformedLinesWithoutThrowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "no-equals-sign\n=empty-key\nGOOD=value\n");

        assertThat(loader.read(file)).containsExactly(Map.entry("GOOD", "value"));
    }

    @Test
    void emptyValueIsKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "BLANK=\n");

        assertThat(loader.read(file)).containsEntry("BLANK", "");
    }

    @Test
    void unreadablePathYieldsEmptyMap(@TempDir Path dir) throws Exception {
        Path notAFile = dir.resolve("a-directory");
        Files.createDirectories(notAFile);

        assertThat(loader.read(notAFile)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=SecretsFileLoaderTest`
Expected: 编译失败 —— `SecretsFileLoader` 不存在。

- [ ] **Step 3: 实现 `SecretsFileLoader`**

```java
package io.github.ecommercebench.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取仓库根 {@code .env} 形式的本地密钥文件。
 *
 * <p>设计上**永不抛异常**：文件缺失、不可读、空文件、畸形行一律降级为「返回已解析部分」，并在 stderr 记一行原因，
 * 使服务在密钥文件不可用时照常启动。兜底必须覆盖 {@link RuntimeException} 而非只 {@link IOException}——本项目已在
 * {@code SupplierRosterReader} 上踩过：第三方解析器把底层错误包成 {@code UncheckedIOException} 抛出，
 * 只 catch 受检异常会让本该降级的路径变成启动崩溃。
 */
public final class SecretsFileLoader {

    /**
     * 解析 {@code KEY=VALUE} 行：忽略空行与 {@code #} 注释，去掉值两侧**成对**的单/双引号。
     *
     * @param file 密钥文件路径，可为 null
     * @return 解析出的键值对（保持文件顺序）；任何异常情况下返回已成功解析的部分，绝不为 null
     */
    public Map<String, String> read(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (file == null) {
            return result;
        }
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                putIfWellFormed(result, raw);
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("[ecbench] 密钥文件不可读，已忽略: " + file + " (" + exception.getClass().getSimpleName() + ")");
        }
        return result;
    }

    /** 跳过空行、注释与不含 {@code =} 或键为空的畸形行；其余按首个 {@code =} 切分。 */
    private static void putIfWellFormed(Map<String, String> target, String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int equals = line.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String key = line.substring(0, equals).trim();
        if (key.isEmpty()) {
            return;
        }
        target.put(key, unquote(line.substring(equals + 1).trim()));
    }

    /** 仅当首尾是**同一种**引号且长度足够时才剥除，避免把 {@code "mismatched'} 改坏。 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=SecretsFileLoaderTest`
Expected: PASS，6 tests。

- [ ] **Step 5: 写 `EffectiveEnvironmentTest`**

```java
package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** 密钥视图合并优先级：真实环境变量优先，.env 只填补缺失项。 */
class EffectiveEnvironmentTest {

    @Test
    void processEnvironmentWinsOverFile() {
        EffectiveEnvironment env =
                new EffectiveEnvironment(Map.of("KEY", "from-process"), Map.of("KEY", "from-file"));

        assertThat(env.asMap()).containsEntry("KEY", "from-process");
    }

    @Test
    void fileFillsMissingKeysOnly() {
        EffectiveEnvironment env =
                new EffectiveEnvironment(Map.of("A", "1"), Map.of("A", "ignored", "B", "2"));

        assertThat(env.asMap()).containsExactlyInAnyOrderEntriesOf(Map.of("A", "1", "B", "2"));
    }

    @Test
    void asMapIsImmutable() {
        EffectiveEnvironment env = new EffectiveEnvironment(Map.of("A", "1"), Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> env.asMap().put("B", "2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultFileIsEnvUnderRepoRoot() {
        java.nio.file.Path workingDir = java.nio.file.Path.of("/tmp/some/working/dir");

        assertThat(EffectiveEnvironment.defaultFile(workingDir).getFileName().toString()).isEqualTo(".env");
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=EffectiveEnvironmentTest`
Expected: 编译失败 —— `EffectiveEnvironment` 不存在。

- [ ] **Step 7: 实现 `EffectiveEnvironment`**

```java
package io.github.ecommercebench.app.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 进程可见的唯一密钥视图：真实环境变量覆盖本地 {@code .env} 文件。
 *
 * <p>优先级刻意定为「env 胜出、文件只填补缺失项」，避免用户 {@code export} 的值被文件静默覆盖。
 * 该视图被三处共用——{@code ApplicationWiring.llmClientProvider()}、{@code ConfigurationMerger} 的 env 入参、
 * 以及 {@code /api/models} 的 {@code keyReady} 判定——从而杜绝「表单说就绪、真跑却缺 key」的漂移。
 */
public final class EffectiveEnvironment {

    private final Map<String, String> merged;

    /** 生产入口：读进程环境变量与仓库根 {@code .env}。 */
    private EffectiveEnvironment() {
        this(System.getenv(), new SecretsFileLoader().read(defaultFile(currentWorkingDir())));
    }

    /** 包级构造：注入两份来源，便于测试。 */
    EffectiveEnvironment(Map<String, String> processEnv, Map<String, String> fileEnv) {
        Map<String, String> result = new HashMap<>(fileEnv);
        result.putAll(processEnv);
        this.merged = Collections.unmodifiableMap(result);
    }

    public static EffectiveEnvironment load() {
        return new EffectiveEnvironment();
    }

    /** 合并后的不可变视图。 */
    public Map<String, String> asMap() {
        return merged;
    }

    /** 密钥文件默认位置：基准根下的 {@code .env}（基准根探测复用 {@link ResourcePaths#repoRoot()}）。 */
    public static Path defaultFile(Path workingDir) {
        return ResourcePaths.of(workingDir).repoRoot().resolve(".env");
    }

    private static Path currentWorkingDir() {
        return Path.of(System.getProperty("user.dir"));
    }
}
```

- [ ] **Step 8: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest='EffectiveEnvironmentTest,SecretsFileLoaderTest'`
Expected: PASS，10 tests。

- [ ] **Step 9: 把 `.env` 加进 `.gitignore`**

在 `.gitignore` 末尾追加两行：

```
# 本地 LLM 密钥文件，绝不入库
.env
```

- [ ] **Step 10: 全量门禁**

Run: `mvn -o clean test`
Expected: BUILD SUCCESS，`ecbench-app` 115 tests（105 + 1 + 6 + 4 = 116？以实际为准，只要求 0 failures / 1 skipped）。

Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: 0 违规。

- [ ] **Step 11: Commit**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/config/SecretsFileLoader.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/config/EffectiveEnvironment.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/config/SecretsFileLoaderTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/config/EffectiveEnvironmentTest.java \
        .gitignore
git commit -m "feat(app): read local .env secrets behind an effective environment view"
```

---

### Task 3: `ProviderResolver.keyEnvironmentFor` 与 `LlmClientProviders`

**Files:**
- Modify: `ecbench-llm/src/main/java/io/github/ecommercebench/llm/provider/ProviderResolver.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/config/LlmClientProviders.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/config/ApplicationWiring.java:34-42`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/config/LlmClientProvidersTest.java`

**Interfaces:**
- Consumes: `EffectiveEnvironment.asMap()`（Task 2）；既有 `ProviderResolver(Map<String,String>)`、`LlmClientFactory(...)`、`RetryExecutor(Sleeper)`、`RetryPolicy.defaults()`、`LlmClientProvider.create(ModelConfig)`。
- Produces: `ProviderResolver.keyEnvironmentFor(String provider) -> String`（未知 provider 返回 null）；`LlmClientProviders.fromEnvironment(Map<String,String> env, ObjectMapper mapper) -> LlmClientProvider`。Task 6 的 `ModelKeyGuard` 与 Task 14 的 `DashboardLauncher` 依赖这两个签名。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ecommercebench.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.domain.error.ConfigurationException;
import io.github.ecommercebench.llm.provider.ProviderResolver;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** provider 预设 env 名暴露与 provider 构造工厂的离线契约（不发任何网络请求）。 */
class LlmClientProvidersTest {

    private static ModelConfig config(String provider, String apiKeyExpression) {
        return new ModelConfig(
                "k", provider, "model-x", "chat", null,
                "openai-compatible".equals(provider) ? "https://example.invalid/v1" : null,
                apiKeyExpression, null, null);
    }

    @Test
    void exposesPresetKeyEnvironmentNames() {
        ProviderResolver resolver = new ProviderResolver(Map.of());

        assertThat(resolver.keyEnvironmentFor("openai")).isEqualTo("OPENAI_API_KEY");
        assertThat(resolver.keyEnvironmentFor("anthropic")).isEqualTo("ANTHROPIC_API_KEY");
        assertThat(resolver.keyEnvironmentFor("google")).isEqualTo("GEMINI_API_KEY");
        assertThat(resolver.keyEnvironmentFor("openrouter")).isEqualTo("OPENROUTER_API_KEY");
    }

    @Test
    void unknownProviderHasNoPresetKeyEnvironment() {
        assertThat(new ProviderResolver(Map.of()).keyEnvironmentFor("nope")).isNull();
    }

    @Test
    void providerFromEnvironmentCreatesClientWhenKeyPresent() {
        LlmClientProvider provider =
                LlmClientProviders.fromEnvironment(Map.of("OPENAI_API_KEY", "sk-test"), new ObjectMapper());

        assertThat(provider.create(config("openai", null))).isNotNull();
    }

    @Test
    void providerFromEnvironmentRejectsMissingKey() {
        LlmClientProvider provider = LlmClientProviders.fromEnvironment(Map.of(), new ObjectMapper());

        assertThatThrownBy(() -> provider.create(config("openai", null)))
                .isInstanceOf(ConfigurationException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app -am test -Dtest=LlmClientProvidersTest`
Expected: 编译失败 —— `keyEnvironmentFor` 与 `LlmClientProviders` 均不存在。

- [ ] **Step 3: 给 `ProviderResolver` 加访问器**

在 `ProviderResolver` 类内（`resolve` 方法之后）追加。**不改 `PRESETS`、不改 `resolve`、不改任何既有行为**：

```java
    /**
     * 返回某 provider 预设的 API Key 环境变量名；未知 provider 返回 null。
     *
     * <p>暴露既有 {@code PRESETS} 映射，供上层判断「该模型的密钥是否已就绪」时复用同一份表，
     * 避免各处另抄一份 provider→变量名对照而漂移。
     */
    public String keyEnvironmentFor(String provider) {
        Preset preset = PRESETS.get(provider);
        return preset == null ? null : preset.keyEnvironment();
    }
```

若 `Preset` 的访问器不叫 `keyEnvironment()`，以 `ProviderResolver` 内 `resolve()` 已有的调用写法和 `PRESETS.get(...)` 用法为准（`resolve` 里已有 `preset.keyEnvironment()` 调用，故该名可用）。

- [ ] **Step 4: 实现 `LlmClientProviders`**

```java
package io.github.ecommercebench.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.llm.LlmClientFactory;
import io.github.ecommercebench.llm.http.RetryExecutor;
import io.github.ecommercebench.llm.http.RetryPolicy;
import io.github.ecommercebench.llm.provider.ProviderResolver;

import java.util.Map;

import org.springframework.web.client.RestClient;

/**
 * {@link LlmClientProvider} 的唯一构造点。
 *
 * <p>Spring 装配路径（{@code ApplicationWiring}）与 Web 触发运行路径（{@code DashboardLauncher}）都调本工厂，
 * 从而「用哪份环境变量、叠什么重试策略」只有一处定义。返回的 provider 是无状态且线程安全的：
 * 既有 {@code RunCoordinator} 已经用多线程并发调用同一 provider 的 {@code create}，跨 job 复用不引入新假设。
 */
public final class LlmClientProviders {

    private LlmClientProviders() {}

    /** 以给定环境变量视图组装 provider：解析器 + 指数退避重试 + 客户端工厂方法引用。 */
    public static LlmClientProvider fromEnvironment(Map<String, String> env, ObjectMapper mapper) {
        ProviderResolver resolver = new ProviderResolver(env);
        RetryExecutor retryExecutor = new RetryExecutor(duration -> Thread.sleep(duration.toMillis()));
        LlmClientFactory factory =
                new LlmClientFactory(
                        resolver, mapper, RestClient.builder(), retryExecutor, RetryPolicy.defaults());
        return factory::create;
    }
}
```

- [ ] **Step 5: 让 `ApplicationWiring` 复用它**

把 `ApplicationWiring` 的整个 `llmClientProvider` 方法（含 Javadoc）替换为：

```java
    /**
     * 默认 LLM 客户端提供者：以「进程环境变量 + 仓库根 .env」的合并视图构造，组装逻辑委托给 {@link LlmClientProviders}。
     */
    @Bean
    public LlmClientProvider llmClientProvider(ObjectMapper objectMapper) {
        return LlmClientProviders.fromEnvironment(EffectiveEnvironment.load().asMap(), objectMapper);
    }
```

删掉随之不再使用的 import：`LlmClientFactory`、`RetryExecutor`、`RetryPolicy`、`ProviderResolver`、`RestClient`（自行核对文件内是否还有其它用处再删）。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app -am test -Dtest=LlmClientProvidersTest`
Expected: PASS，4 tests。

- [ ] **Step 7: 全量门禁**

Run: `mvn -o clean test`
Expected: BUILD SUCCESS，7 模块，0 failures，1 skipped。

**不要**对 `ecbench-llm` 跑 checkstyle（存量 4 处红）。只对 app/evaluation 跑：
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: 0 违规。

- [ ] **Step 8: Commit**

```bash
git add ecbench-llm/src/main/java/io/github/ecommercebench/llm/provider/ProviderResolver.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/config/LlmClientProviders.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/config/ApplicationWiring.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/config/LlmClientProvidersTest.java
git commit -m "refactor(llm,app): single construction point for the LLM client provider"
```

---

### Task 4: `RunComponentFactory` 的会话目录回调

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/run/RunComponentFactory.java:80-91,228-239`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/run/RunComponentFactoryTest.java`（新建）

**Interfaces:**
- Consumes: 既有 `RunDirectory.create(Path logDir, String timestamp, String model)`；`BenchmarkOptions` / `RunConfig`。
- Produces: 构造重载 `RunComponentFactory(ObjectMapper, LlmClientProvider, Consumer<Path> onSessionCreated)`；包级方法 `RunDirectory sharedRunDirectory(BenchmarkOptions options)`（原 `private`）。回调参数是**会话目录本身**（如 `<logDir>/20261014_100001_qwen3.7-max`）。Task 9 的 `BenchmarkRunLauncher` 依赖这个构造重载。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ecommercebench.app.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.ConfigurationMerger;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.log.RunDirectory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话目录创建回调契约：目录建好后触发一次，且既有 2 参构造行为不变。 */
class RunComponentFactoryTest {

    private static final String CONFIG_JSON =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {"gpt": {"provider": "openai", "model_name": "gpt-x"}}
                    }
                    """;

    private static final LlmClientProvider NO_PROVIDER = config -> null;

    /** 只填 model 与 logDir 的最小 RunOptions，避免触发 catalog / tokenizer 加载。 */
    private record MinimalOptions(String model, String logDir) implements RunOptions {
        @Override public int maxTokens() { return 16384; }
        @Override public int maxTurns() { return 4000; }
        @Override public int maxDays() { return 365; }
        @Override public double initialBalance() { return 100000.0; }
        @Override public double dailyFee() { return 50.0; }
        @Override public int maxTokenCapacity() { return 128000; }
        @Override public String tokenizerPath() { return null; }
        @Override public String jobFile() { return null; }
        @Override public int runs() { return 1; }
        @Override public Long seed() { return null; }
        @Override public String dataDir() { return null; }
    }

    private static BenchmarkOptions optionsFor(Path workingDir, Path logDir) throws Exception {
        Files.writeString(workingDir.resolve("models_config.json"), CONFIG_JSON);
        RunOptions cli = new MinimalOptions("gpt", logDir.toString());
        return new ConfigurationMerger().merge(cli, Map.of(), workingDir);
    }

    @Test
    void notifiesSessionDirectoryOnceCreated(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        List<Path> seen = new ArrayList<>();
        RunComponentFactory factory =
                new RunComponentFactory(new ObjectMapper(), NO_PROVIDER, seen::add);

        RunDirectory directory = factory.sharedRunDirectory(optionsFor(workingDir, logDir));

        assertThat(seen).containsExactly(directory.sessionDir());
        assertThat(Files.isDirectory(directory.sessionDir())).isTrue();
        assertThat(directory.sessionDir().getFileName().toString()).endsWith("_gpt");
    }

    @Test
    void secondCallReusesCachedDirectoryAndDoesNotNotifyAgain(
            @TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        List<Path> seen = new ArrayList<>();
        RunComponentFactory factory =
                new RunComponentFactory(new ObjectMapper(), NO_PROVIDER, seen::add);
        BenchmarkOptions options = optionsFor(workingDir, logDir);

        RunDirectory first = factory.sharedRunDirectory(options);
        RunDirectory second = factory.sharedRunDirectory(options);

        assertThat(second).isSameAs(first);
        assertThat(seen).hasSize(1);
    }

    @Test
    void twoArgConstructorStillWorksAndNotifiesNobody(
            @TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        RunComponentFactory factory = new RunComponentFactory(new ObjectMapper(), NO_PROVIDER);

        RunDirectory directory = factory.sharedRunDirectory(optionsFor(workingDir, logDir));

        assertThat(Files.isDirectory(directory.metricsDir())).isTrue();
    }
}
```

`@TempDir` 不能用在私有静态辅助方法的参数上（JUnit 只注入到测试方法与实例字段），所以 `optionsFor` 必须显式接收两个 `Path` 形参。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=RunComponentFactoryTest`
Expected: 编译失败 —— 三参构造不存在，且 `sharedRunDirectory` 是 `private`。

- [ ] **Step 3: 加字段、构造重载与回调**

在 `RunComponentFactory` 的字段区（`private List<RunJob> fileJobs;` 之后）加：

```java
    private final Consumer<Path> onSessionCreated;
```

把既有的两参构造替换为两个构造：

```java
    /** 注入共享 JSON mapper 与 LLM 客户端提供者；会话目录回调为 no-op。 */
    public RunComponentFactory(ObjectMapper objectMapper, LlmClientProvider llmClientProvider) {
        this(objectMapper, llmClientProvider, ignored -> {});
    }

    /**
     * 注入共享 JSON mapper、LLM 客户端提供者与会话目录回调。
     *
     * <p>回调在 {@link #sharedRunDirectory} 首次创建 {@code {timestamp}_{model}} 会话目录后触发一次，
     * 供 Web 触发运行的任务把会话名回填给前端；CLI 路径用两参构造，回调为 no-op。
     */
    public RunComponentFactory(
            ObjectMapper objectMapper,
            LlmClientProvider llmClientProvider,
            Consumer<Path> onSessionCreated) {
        this.objectMapper = objectMapper;
        this.llmClientProvider = llmClientProvider;
        this.onSessionCreated = onSessionCreated;
    }
```

import 区加 `import java.util.function.Consumer;`。

- [ ] **Step 4: 改 `sharedRunDirectory` 的可见性并触发回调**

把整个方法替换为（可见性 `private` → 包级，并在返回前触发回调）：

```java
    /**
     * 惰性创建并共享本次运行的产物目录（缺省落在 ./log，含时间戳与模型名）；创建后触发一次会话回调。
     *
     * <p>包级可见以便测试直接验证回调，无需装配完整的 catalog / tokenizer / LLM 依赖。
     */
    synchronized RunDirectory sharedRunDirectory(BenchmarkOptions options) {
        if (runDirectory == null) {
            Path logDir =
                    options.runConfig().logDir() != null
                            ? options.runConfig().logDir()
                            : Path.of(System.getProperty("user.dir")).resolve("log");
            runDirectory =
                    RunDirectory.create(
                            logDir, TIMESTAMP.format(LocalDateTime.now()), options.runConfig().modelKey());
            onSessionCreated.accept(runDirectory.sessionDir());
        }
        return runDirectory;
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=RunComponentFactoryTest`
Expected: PASS，3 tests。

- [ ] **Step 6: 全量门禁**

Run: `mvn -o clean test`
Expected: BUILD SUCCESS，0 failures，1 skipped。

Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`
Expected: 0 违规。

- [ ] **Step 7: Commit**

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/run/RunComponentFactory.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/run/RunComponentFactoryTest.java
git commit -m "feat(app): notify the created session directory from RunComponentFactory"
```

---

### Task 5: `app.job` 值类型：`JobState` / `Job` / `RunLauncher` / `JobRequest`

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobState.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/Job.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/RunLauncher.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobRequest.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobRequestTest.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobTest.java`

**Interfaces:**
- Consumes: `RunOptions`（Task 1）；既有 `RunOutcome`、`BenchmarkOptions`。
- Produces:
  - `enum JobState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }` + `boolean terminal()`
  - `Job(String id, JobRequest request)`；访问器 `id() / request() / state() / submittedAt() / startedAt() / finishedAt() / sessionName() / error() / outcomes() / future()`；迁移方法 `attach(Future<?>) / setSessionName(String) / markRunning() -> boolean / markCancelled() / complete(List<RunOutcome>) / fail(Throwable)`
  - `interface RunLauncher { List<RunOutcome> launch(BenchmarkOptions options, Consumer<Path> onSessionCreated); }`
  - `record JobRequest(...) implements RunOptions` + `static JobRequest parse(JsonNode body, RunOptions server)`

**关键实现约束（务必照做）**：`seed` 在 body 缺省时必须是 **`null`**，不要在 `JobRequest` 里硬编码 42。42 这个默认值由 `ConfigurationMerger.DEFAULT_SEED` 唯一持有，`CliRunOptions.seed()` 缺省也是 `null`。硬编码 42 会造成两处默认值来源，将来改 `ConfigurationMerger` 时 Web 与 CLI 会静默分叉。

- [ ] **Step 1: 写 `JobState` 与 `RunLauncher`**

`JobState.java`：

```java
package io.github.ecommercebench.app.job;

/** Web 提交的运行任务的生命周期状态。 */
public enum JobState {
    /** 已接受，在串行队列中等待。 */
    QUEUED,
    /** 正在执行。 */
    RUNNING,
    /** 全部 run 正常结束（个别 run 失败仍算 SUCCEEDED，失败详情见 outcomes）。 */
    SUCCEEDED,
    /** 编排层抛出未捕获异常。 */
    FAILED,
    /** 被 DELETE 取消，或服务关闭时中断。 */
    CANCELLED;

    /** 是否为终态：终态之后不允许再迁出。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
```

`RunLauncher.java`：

```java
package io.github.ecommercebench.app.job;

import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 执行一次基准运行的接缝。
 *
 * <p>生产实现是 {@link BenchmarkRunLauncher}；测试用假实现驱动 {@code JobService} 的整套状态机与取消逻辑，
 * 完全不碰 LLM 与仿真引擎。
 *
 * <p>{@code onSessionCreated} 必须透传给 {@code RunComponentFactory} 的三参构造，以便会话目录一建好就把名字
 * 回填给任务，前端才能在运行中（而不是结束后）看到可点击的会话名。
 */
@FunctionalInterface
public interface RunLauncher {

    /**
     * 跑完 options 指定的全部 run。
     *
     * @param options 已合并的运行配置
     * @param onSessionCreated 会话目录创建后回调一次，参数为该目录
     * @return 各 run 的结果，按 index 升序
     */
    List<RunOutcome> launch(BenchmarkOptions options, Consumer<Path> onSessionCreated);
}
```

- [ ] **Step 2: 写 `JobTest`（失败测试）**

```java
package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.app.run.RunOutcome;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 任务状态机：只允许从非终态迁出，取消与完成竞态时以先落地的终态为准。 */
class JobTest {

    private static JobRequest request() {
        return new JobRequest("gpt", 16384, 4000, 365, 100000.0, 50.0, 128000, 1, null,
                null, null, null, null);
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
        assertThat(job.startedAt()).isNotNull();
        assertThat(job.finishedAt()).isNotNull();
        assertThat(job.outcomes()).hasSize(1);
        assertThat(JobState.SUCCEEDED.terminal()).isTrue();
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
    void markRunningOnCancelledJobReturnsFalse() {
        Job job = newJob();
        job.markCancelled();

        assertThat(job.markRunning()).isFalse();
        assertThat(job.state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void failureRecordsSimpleClassNameAndMessageOnly() {
        Job job = newJob();
        job.markRunning();

        job.fail(new IllegalStateException("boom\nat some.Stack(Frame.java:1)"));

        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.error()).isEqualTo("IllegalStateException: boom");
        assertThat(job.error()).doesNotContain("Stack");
    }

    @Test
    void sessionNameIsRecordedForProgressLookup() {
        Job job = newJob();

        job.setSessionName("20261014_100001_gpt");

        assertThat(job.sessionName()).isEqualTo("20261014_100001_gpt");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=JobTest`
Expected: 编译失败 —— `Job` / `JobRequest` 不存在。

- [ ] **Step 4: 实现 `Job`**

```java
package io.github.ecommercebench.app.job;

import io.github.ecommercebench.app.run.RunOutcome;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;

/**
 * 单个 Web 提交任务的可变状态载体。
 *
 * <p>状态迁移全部在 {@code synchronized} 方法内完成，且**只允许从非终态迁出**，因此「取消」与「正常完成」同时到达时
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

    /** 失败收尾：仅在非终态生效；错误文本只留异常简单类名与首行消息，不含堆栈。 */
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
```

- [ ] **Step 5: 跑 `JobTest` 前的准备——先写 `JobRequest`**

`JobRequest.java`：

```java
package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ecommercebench.app.config.RunOptions;

/**
 * 一次 Web 提交的运行参数，与 {@code CliRunOptions} 共享 {@link RunOptions} 契约。
 *
 * <p>13 个字段中**只有 9 个可由 HTTP 提交**（model / maxTokens / maxTurns / maxDays / initialBalance /
 * dailyFee / maxTokenCapacity / runs / seed）；4 个路径类字段（tokenizerPath / logDir / jobFile / dataDir）
 * 一律取自服务进程自身的启动配置，**不接受 body 输入**——否则本端点就成了任意路径写入入口。
 *
 * <p>缺省值与 {@code CliRunOptions} 的 {@code @Option(defaultValue=…)} 逐字相同；{@code seed} 缺省为 {@code null}
 * 而非 42，因为 42 由 {@code ConfigurationMerger.DEFAULT_SEED} 唯一持有，两处各写一份会静默分叉。
 */
public record JobRequest(
        String model,
        int maxTokens,
        int maxTurns,
        int maxDays,
        double initialBalance,
        double dailyFee,
        int maxTokenCapacity,
        int runs,
        Long seed,
        String tokenizerPath,
        String logDir,
        String jobFile,
        String dataDir)
        implements RunOptions {

    public static final int DEFAULT_MAX_TOKENS = 16384;
    public static final int DEFAULT_MAX_TURNS = 4000;
    public static final int DEFAULT_MAX_DAYS = 365;
    public static final double DEFAULT_INITIAL_BALANCE = 100000.0;
    public static final double DEFAULT_DAILY_FEE = 50.0;
    public static final int DEFAULT_MAX_TOKEN_CAPACITY = 128000;
    public static final int DEFAULT_RUNS = 1;

    /**
     * 从 POST body 解析请求：校验类型与取值范围，非法输入抛 {@code IllegalArgumentException}（路由层转 400）。
     *
     * @param body 请求体 JSON
     * @param server 服务进程自身的启动参数，只用于取 4 个路径类字段
     */
    public static JobRequest parse(JsonNode body, RunOptions server) {
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
                server.jobFile(),
                server.dataDir());
    }

    private static String trimmedText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText().trim();
    }

    private static int positiveInt(JsonNode body, String field, int fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        requireIntegral(node, field);
        int value = node.asInt();
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
        return value;
    }

    private static double positiveDouble(JsonNode body, String field, double fallback) {
        double value = doubleOf(body, field, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正数");
        }
        return value;
    }

    private static double nonNegativeDouble(JsonNode body, String field, double fallback) {
        double value = doubleOf(body, field, fallback);
        if (value < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
        return value;
    }

    private static double doubleOf(JsonNode body, String field, double fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException(field + " 必须是数字");
        }
        return node.asDouble();
    }

    private static Long optionalLong(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        requireIntegral(node, field);
        return node.asLong();
    }

    /** 拒绝 1.5 这类小数与字符串数字，避免 asInt 静默截断。 */
    private static void requireIntegral(JsonNode node, String field) {
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
    }
}
```

- [ ] **Step 6: 跑 `JobTest` 确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=JobTest`
Expected: PASS，7 tests。

- [ ] **Step 7: 写 `JobRequestTest`（防默认值漂移的关键测试）**

```java
package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.cli.CliRunOptions;
import io.github.ecommercebench.app.config.RunOptions;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** JobRequest 与 CliRunOptions 的契约一致性：访问器集合、默认值、路径类字段不可由 body 覆盖。 */
class JobRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 服务进程启动参数：4 个路径类字段的来源。 */
    private static final RunOptions SERVER =
            new JobRequest("unused", 1, 1, 1, 1.0, 1.0, 1, 1, null,
                    "/srv/tokenizer", "/srv/log", "/srv/job.jsonl", "/srv/data");

    private static CliRunOptions cli(String... args) {
        CliRunOptions options = new CliRunOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }

    private static ObjectNode body(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    @Test
    void recordComponentsMatchTheRunOptionsContractExactly() {
        Set<String> contract =
                Arrays.stream(RunOptions.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(Collectors.toSet());
        Set<String> components =
                Arrays.stream(JobRequest.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toSet());

        assertThat(components).isEqualTo(contract);
        assertThat(contract).hasSize(13);
    }

    @Test
    void cliRunOptionsExposesEveryContractAccessor() {
        Set<String> contract =
                Arrays.stream(RunOptions.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(Collectors.toSet());
        Set<String> cliAccessors =
                Arrays.stream(CliRunOptions.class.getDeclaredMethods())
                        .filter(method -> method.getParameterCount() == 0)
                        .map(Method::getName)
                        .collect(Collectors.toSet());

        assertThat(cliAccessors).containsAll(contract);
    }

    @Test
    void defaultsMatchCliRunOptionsValueForValue() throws Exception {
        CliRunOptions cli = cli("--model", "gpt");

        JobRequest web = JobRequest.parse(body("{\"model\":\"gpt\"}"), SERVER);

        assertThat(web.maxTokens()).isEqualTo(cli.maxTokens());
        assertThat(web.maxTurns()).isEqualTo(cli.maxTurns());
        assertThat(web.maxDays()).isEqualTo(cli.maxDays());
        assertThat(web.initialBalance()).isEqualTo(cli.initialBalance());
        assertThat(web.dailyFee()).isEqualTo(cli.dailyFee());
        assertThat(web.maxTokenCapacity()).isEqualTo(cli.maxTokenCapacity());
        assertThat(web.runs()).isEqualTo(cli.runs());
        assertThat(web.seed()).isEqualTo(cli.seed()).isNull();
    }

    @Test
    void pathFieldsAlwaysComeFromServerNeverFromBody() throws Exception {
        ObjectNode node = body("{\"model\":\"gpt\"}");
        node.put("logDir", "/attacker/log");
        node.put("dataDir", "/attacker/data");
        node.put("tokenizerPath", "/attacker/tokenizer.json");
        node.put("jobFile", "/attacker/job.jsonl");

        JobRequest web = JobRequest.parse(node, SERVER);

        assertThat(web.logDir()).isEqualTo("/srv/log");
        assertThat(web.dataDir()).isEqualTo("/srv/data");
        assertThat(web.tokenizerPath()).isEqualTo("/srv/tokenizer");
        assertThat(web.jobFile()).isEqualTo("/srv/job.jsonl");
    }

    @Test
    void explicitNumbersOverrideDefaults() throws Exception {
        JobRequest web =
                JobRequest.parse(
                        body("{\"model\":\"gpt\",\"maxDays\":10,\"maxTurns\":50,\"runs\":3,\"seed\":7}"),
                        SERVER);

        assertThat(web.maxDays()).isEqualTo(10);
        assertThat(web.maxTurns()).isEqualTo(50);
        assertThat(web.runs()).isEqualTo(3);
        assertThat(web.seed()).isEqualTo(7L);
    }

    @Test
    void rejectsMissingBlankAndMalformedModel() {
        assertThatThrownBy(() -> JobRequest.parse(null, SERVER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"  \"}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void rejectsNonPositiveAndNonIntegralNumbers() {
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"maxDays\":0}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDays");
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"runs\":1.5}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs");
        assertThatThrownBy(() -> JobRequest.parse(body("{\"model\":\"g\",\"dailyFee\":-1}"), SERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyFee");
    }
}
```

- [ ] **Step 8: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest='JobRequestTest,JobTest'`
Expected: PASS，14 tests。

- [ ] **Step 9: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/job/ \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/
git commit -m "feat(app): add job value types shared with the CLI run contract"
```

---

### Task 6: `ModelKeyGuard` —— 密钥就绪判定的唯一实现点

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/ModelKeyGuard.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/ModelKeyGuardTest.java`

**Interfaces:**
- Consumes: `ProviderResolver.keyEnvironmentFor(String)`（Task 3）；既有 `ModelRegistryLoader(ObjectMapper).load(Path)`、`ModelRegistry.models()` / `.npcModel()` / `.resolve(String)`、`ModelConfig.apiKeyExpression()` / `.provider()` / `.modelName()` / `.key()`；`ConfigurationException`。
- Produces:
  - `ModelKeyGuard(Path modelsConfigPath, Map<String,String> env, ObjectMapper mapper)`
  - `ModelRegistry registry()`
  - `String missingEnv(String modelKey)` —— 就绪返回 `null`；模型键未知抛 `ConfigurationException`
  - `String npcMissingEnv()`
  - `void requireReady(String modelKey)` —— 缺密钥抛 `IllegalArgumentException`（→ 400），模型键未知抛 `IllegalArgumentException`（→ 400，附可用键数量）
- 被 Task 7 的 `JobService`（提交拦截）与 Task 11 的 `ModelCatalogService`（列表标注）**共用**。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 密钥就绪判定：${VAR} 表达式与 provider 预设两条推断路径，以及 NPC 模型的独立判定。 */
class ModelKeyGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {
                        "gpt": {"provider": "openai", "model_name": "gpt-x"},
                        "qwen": {"provider": "openai-compatible", "model_name": "qwen-x",
                                 "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                 "api_key": "${DASHSCOPE_API_KEY}"}
                      }
                    }
                    """;

    private static ModelKeyGuard guard(Path dir, Map<String, String> env) throws Exception {
        Files.writeString(dir.resolve("models_config.json"), CONFIG);
        return new ModelKeyGuard(dir.resolve("models_config.json"), env, MAPPER);
    }

    @Test
    void presetProviderUsesItsDefaultVariable(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of());

        assertThat(guard.missingEnv("gpt")).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void expressionProviderUsesTheDeclaredVariable(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of());

        assertThat(guard.missingEnv("qwen")).isEqualTo("DASHSCOPE_API_KEY");
    }

    @Test
    void presentNonBlankValueMeansReady(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("OPENAI_API_KEY", "sk-x", "DASHSCOPE_API_KEY", "sk-y"));

        assertThat(guard.missingEnv("gpt")).isNull();
        assertThat(guard.missingEnv("qwen")).isNull();
    }

    @Test
    void blankValueCountsAsMissing(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("OPENAI_API_KEY", "   "));

        assertThat(guard.missingEnv("gpt")).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void npcKeyIsJudgedIndependently(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("DASHSCOPE_API_KEY", "sk-y"));

        assertThat(guard.npcMissingEnv()).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void requireReadyNamesTheMissingVariable(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of());

        assertThatThrownBy(() -> guard.requireReady("qwen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void requireReadyAlsoChecksTheNpcModel(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("DASHSCOPE_API_KEY", "sk-y"));

        assertThatThrownBy(() -> guard.requireReady("qwen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageContaining("npc_tools");
    }

    @Test
    void unknownModelKeyIsRejectedWithTheAvailableCount(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of("OPENAI_API_KEY", "sk-x"));

        assertThatThrownBy(() -> guard.requireReady("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("2");
    }

    @Test
    void registryIsExposedForListing(@TempDir Path dir) throws Exception {
        ModelKeyGuard guard = guard(dir, Map.of());

        assertThat(guard.registry().models()).containsOnlyKeys("gpt", "qwen");
        assertThat(guard.registry().npcModel().modelName()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void nullConfigPathYieldsEmptyRegistry() {
        ModelKeyGuard guard = new ModelKeyGuard(null, Map.of(), MAPPER);

        assertThat(guard.registry().models()).isEmpty();
        assertThatThrownBy(() -> guard.requireReady("gpt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`nullConfigPathYieldsEmptyRegistry` 不是装饰：Task 7 的 `JobServiceTest` 与 Task 8 的 `JobProgressTest` 都靠它构造一个「永远判定就绪」的 guard，从而不必准备 `models_config.json`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=ModelKeyGuardTest`
Expected: 编译失败 —— `ModelKeyGuard` 不存在。

- [ ] **Step 3: 实现 `ModelKeyGuard`**

```java
package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.domain.config.ModelConfig;
import io.github.ecommercebench.domain.config.ModelRegistry;
import io.github.ecommercebench.domain.config.ModelRegistryLoader;
import io.github.ecommercebench.llm.provider.ProviderResolver;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「某个模型键现在能不能跑」的唯一判定实现。
 *
 * <p>{@code JobService} 用它做提交拦截，{@code ModelCatalogService} 用它标注下拉列表，两处共用同一份推断，
 * 从而杜绝「表单说就绪、真跑却缺 key」的漂移。
 *
 * <p>变量名的推断顺序与 {@code ProviderResolver.resolve} 一致：有 {@code apiKeyExpression} 时按 {@code ${VAR}}
 * 展开，否则取 provider 预设名（经 {@link ProviderResolver#keyEnvironmentFor}，不另抄一份对照表）。
 * 但**就绪与否直接查合并后的环境变量视图**，而不是靠捕获 {@code ConfigurationException}——后者的错误信息对
 * {@code openai-compatible} + 自定义表达式的模型会误报成 {@code OPENAI_API_KEY}（既有缺陷，本次不修）。
 */
public final class ModelKeyGuard {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private final ModelRegistry registry;
    private final ProviderResolver resolver;
    private final Map<String, String> env;

    public ModelKeyGuard(Path modelsConfigPath, Map<String, String> env, ObjectMapper mapper) {
        this.registry = loadRegistry(modelsConfigPath, mapper);
        this.resolver = new ProviderResolver(env);
        this.env = Map.copyOf(env);
    }

    /** 路径为 null 时给出空注册表（仅供测试注入）；生产路径总是非 null。 */
    private static ModelRegistry loadRegistry(Path modelsConfigPath, ObjectMapper mapper) {
        if (modelsConfigPath == null) {
            return new ModelRegistry(
                    Map.of(),
                    new ModelConfig("npc_tools", "openai", "none", null, null, null, null, null, null));
        }
        return new ModelRegistryLoader(mapper).load(modelsConfigPath);
    }

    /** 已加载的模型注册表，供列表端点枚举模型键。 */
    public ModelRegistry registry() {
        return registry;
    }

    /** 该模型键缺失的环境变量名；已就绪返回 null。模型键未知抛 {@code IllegalArgumentException}。 */
    public String missingEnv(String modelKey) {
        return missingEnvFor(requireModel(modelKey));
    }

    /** 供应商 NPC 模型缺失的环境变量名；已就绪返回 null。 */
    public String npcMissingEnv() {
        return missingEnvFor(registry.npcModel());
    }

    /**
     * 校验主模型与 NPC 模型均已就绪，否则抛 {@code IllegalArgumentException}（路由层转 400）。
     *
     * <p>两个模型都要查：{@code RunComponentFactory} 每个 run 都会额外为 {@code npcModel} 建一个客户端，
     * 因此即便主模型选的是 qwen，缺 {@code OPENAI_API_KEY} 一样跑不起来。
     */
    public void requireReady(String modelKey) {
        ModelConfig config = requireModel(modelKey);
        String missing = missingEnvFor(config);
        if (missing != null) {
            throw new IllegalArgumentException("模型 " + modelKey + " 缺少环境变量 " + missing);
        }
        String npcMissing = missingEnvFor(registry.npcModel());
        if (npcMissing != null) {
            throw new IllegalArgumentException(
                    "供应商模型 npc_tools 缺少环境变量 " + npcMissing + "（每个 run 都需要它渲染对话）");
        }
    }

    private ModelConfig requireModel(String modelKey) {
        ModelConfig config = registry.models().get(modelKey);
        if (config == null) {
            throw new IllegalArgumentException(
                    "models_config.json 中不存在模型: " + modelKey + "（可用 " + registry.models().size() + " 个）");
        }
        return config;
    }

    private String missingEnvFor(ModelConfig config) {
        String variable = envVarName(config);
        if (variable == null) {
            return null;
        }
        String value = env.get(variable);
        return value == null || value.isBlank() ? variable : null;
    }

    /** 有 ${VAR} 表达式则取其中的变量名；否则取 provider 预设名；都取不到则视为无需密钥。 */
    private String envVarName(ModelConfig config) {
        String expression = config.apiKeyExpression();
        if (expression != null && !expression.isBlank()) {
            Matcher matcher = EXPRESSION.matcher(expression);
            return matcher.find() ? matcher.group(1) : null;
        }
        return resolver.keyEnvironmentFor(config.provider());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=ModelKeyGuardTest`
Expected: PASS，10 tests。

- [ ] **Step 5: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/job/ModelKeyGuard.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/ModelKeyGuardTest.java
git commit -m "feat(app): centralize LLM key readiness checks for web submissions"
```

---

### Task 7: `JobService` —— 串行队列、状态机与取消

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobService.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiException.java`（本任务新建，见 Step 1）
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobServiceTest.java`

**Interfaces:**
- Consumes: `RunLauncher`、`JobRequest.parse`、`Job`、`JobState`、`ModelKeyGuard.requireReady`（Task 5/6）；`ConfigurationMerger.merge(RunOptions, Map, Path)`（Task 1）；`RunOutcome`。
- Produces:
  - `ApiException(int status, String message)` + `int status()`（放在 `app.web`，Task 10 的路由层要用）
  - `JobService(RunLauncher launcher, ModelKeyGuard keys, RunOptions serverDefaults, Map<String,String> env, Path workingDir, ObjectMapper mapper)`
  - `Job submit(JsonNode body)`、`Job get(String id)`、`List<Job> list()`、`Job cancel(String id)`、`void shutdown()`
  - 常量 `JobService.MAX_QUEUED = 8`
  - Task 8 会在同一个类上追加 `jobsNode()` / `jobNode(Job)`。

- [ ] **Step 1: 新建 `ApiException`**

`ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiException.java`：

```java
package io.github.ecommercebench.app.web;

/**
 * 携带 HTTP 状态码的 API 异常，用于既有映射覆盖不到的 403 / 409 / 413。
 *
 * <p>{@code ApiRouter.handle} 的 catch 链中本类**必须排在 {@code RuntimeException} 之前**，否则会被兜底分支
 * 转成 500。400 仍走 {@code IllegalArgumentException}、404 仍走 {@code ArtifactNotFoundException}，不迁移既有约定。
 */
public final class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** 应回的 HTTP 状态码。 */
    public int status() {
        return status;
    }
}
```

- [ ] **Step 2: 写 `JobServiceTest`（失败测试）**

```java
package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.run.RunOutcome;
import io.github.ecommercebench.app.web.ApiException;
import io.github.ecommercebench.app.web.ArtifactNotFoundException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 任务编排契约：串行、状态机、取消、队列上限。全部用假 launcher，不碰 LLM 与仿真引擎。 */
class JobServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RunOptions SERVER =
            new JobRequest("unused", 1, 1, 1, 1.0, 1.0, 1, 1, null, null, null, null, null);

    private JobService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.shutdown();
        }
    }

    /** 假 guard：永远判定就绪，让测试不必准备 models_config.json。 */
    private static ModelKeyGuard readyGuard() {
        return new ModelKeyGuard(null, Map.of(), MAPPER) {
            @Override public void requireReady(String modelKey) {}
        };
    }

    private JobService serviceWith(RunLauncher launcher, @TempDir Path unused) {
        service = new JobService(
                launcher, readyGuard(), SERVER, Map.of(), Path.of("."), MAPPER);
        return service;
    }

    private static ObjectNode body() throws Exception {
        return (ObjectNode) MAPPER.readTree("{\"model\":\"gpt\",\"maxDays\":3}");
    }

    @Test
    void submittedJobRunsAndSucceeds(@TempDir Path dir) throws Exception {
        AtomicInteger launches = new AtomicInteger();
        JobService svc = serviceWith((options, onSession) -> {
            launches.incrementAndGet();
            return List.of(new RunOutcome(0, null, null));
        }, dir);

        Job job = svc.submit(body());
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThat(launches.get()).isEqualTo(1);
        assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.outcomes()).hasSize(1);
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    void launcherFailureMarksJobFailed(@TempDir Path dir) throws Exception {
        JobService svc = serviceWith((options, onSession) -> {
            throw new IllegalStateException("kaboom");
        }, dir);

        Job job = svc.submit(body());
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.error()).isEqualTo("IllegalStateException: kaboom");
    }

    @Test
    void jobsRunStrictlySerially(@TempDir Path dir) throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        JobService svc = serviceWith((options, onSession) -> {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            firstStarted.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return List.of();
        }, dir);

        Job first = svc.submit(body());
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Job second = svc.submit(body());
        assertThat(second.state()).isEqualTo(JobState.QUEUED);
        releaseFirst.countDown();
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThat(peak.get()).isEqualTo(1);
        assertThat(first.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(second.state()).isEqualTo(JobState.SUCCEEDED);
    }

    @Test
    void cancelInterruptsRunningJob(@TempDir Path dir) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        JobService svc = serviceWith((options, onSession) -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("被中断");
            }
            return List.of();
        }, dir);

        Job job = svc.submit(body());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Job cancelled = svc.cancel(job.id());
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThat(cancelled.state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void cancelFinishedJobIsConflict(@TempDir Path dir) throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of(), dir);
        Job job = svc.submit(body());
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> svc.cancel(job.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(409));
    }

    @Test
    void unknownJobIdIsNotFound(@TempDir Path dir) {
        JobService svc = serviceWith((options, onSession) -> List.of(), dir);

        assertThatThrownBy(() -> svc.get("nope"))
                .isInstanceOf(ArtifactNotFoundException.class);
        assertThatThrownBy(() -> svc.cancel("nope"))
                .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void queueBeyondLimitIsConflict(@TempDir Path dir) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobService svc = serviceWith((options, onSession) -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }, dir);

        svc.submit(body());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < JobService.MAX_QUEUED - 1; i++) {
            svc.submit(body());
        }

        assertThatThrownBy(() -> svc.submit(body()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(409));
        release.countDown();
    }

    @Test
    void listingIsInSubmissionOrder(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobService svc = serviceWith((options, onSession) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }, dir);

        Job a = svc.submit(body());
        Job b = svc.submit(body());
        release.countDown();
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThat(svc.list()).containsExactly(a, b);
    }

    @Test
    void sessionCallbackReachesTheJob(@TempDir Path dir) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        JobService svc = serviceWith((options, onSession) -> {
            onSession.accept(dir.resolve("20261014_100001_gpt"));
            done.countDown();
            return List.of();
        }, dir);

        Job job = svc.submit(body());
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThat(job.sessionName()).isEqualTo("20261014_100001_gpt");
    }

    @Test
    void submitPropagatesRequestValidationErrors(@TempDir Path dir) throws Exception {
        JobService svc = serviceWith((options, onSession) -> List.of(), dir);
        ObjectNode bad = (ObjectNode) MAPPER.readTree("{\"maxDays\":3}");

        assertThatThrownBy(() -> svc.submit(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

`readyGuard()` 用匿名子类覆盖 `requireReady`，依赖 Task 6 已实现的「`modelsConfigPath == null` → 空注册表」分支，因此无需在测试里准备 `models_config.json`。

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=JobServiceTest`
Expected: 编译失败 —— `JobService` 不存在。

- [ ] **Step 4: 实现 `JobService`（本任务只做编排，不含 JSON 序列化）**

```java
package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.ConfigurationMerger;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.run.RunOutcome;
import io.github.ecommercebench.app.web.ApiException;
import io.github.ecommercebench.app.web.ArtifactNotFoundException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Web 提交运行的任务编排：内存注册表 + **单线程串行**执行器 + 状态机 + 尽力而为的取消。
 *
 * <p>串行的理由：一个 365 天 episode 是小时级任务且打满 LLM 配额；若允许多 job 并行，每个 job 内部
 * {@code RunCoordinator} 又会开 {@code min(runs, 核数)} 个线程，线程数乘法级膨胀。job 内部仍可用 {@code runs} 并行。
 *
 * <p>状态只存内存，服务重启即清空（历史产物仍在 {@code log/} 下，会话扫描照常可见）。
 */
public final class JobService {

    /** 非终态任务（含正在跑的）总数上限，超出则 409。 */
    public static final int MAX_QUEUED = 8;

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

    /**
     * 接受一次提交：校验参数与密钥后就入队，立即返回 QUEUED 任务。
     *
     * @throws IllegalArgumentException 参数非法或密钥缺失（→ 400）
     * @throws ApiException 队列已满（409）
     */
    public Job submit(JsonNode body) {
        JobRequest request = JobRequest.parse(body, serverDefaults);
        keys.requireReady(request.model());
        synchronized (jobs) {
            long pending = jobs.values().stream().filter(job -> !job.state().terminal()).count();
            if (pending >= MAX_QUEUED) {
                throw new ApiException(409, "任务队列已满（上限 " + MAX_QUEUED + " 个未结束任务）");
            }
            Job job = new Job(UUID.randomUUID().toString(), request);
            jobs.put(job.id(), job);
            job.attach(executor.submit(() -> execute(job)));
            return job;
        }
    }

    /** 按 id 取任务；未知 id 抛 {@link ArtifactNotFoundException}（→ 404）。 */
    public Job get(String id) {
        synchronized (jobs) {
            Job job = jobs.get(id);
            if (job == null) {
                throw new ArtifactNotFoundException("任务不存在: " + id);
            }
            return job;
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
     * <p>中断会级联到 {@code RunCoordinator} 阻塞的 {@code invokeAll}，进而 {@code cancel(true)} 各 run 线程；
     * 但 agent 主循环不检查中断，若中断落在纯计算段要等下一个阻塞点才生效。
     *
     * @throws ArtifactNotFoundException 未知 id（→ 404）
     * @throws ApiException 任务已终态（409）
     */
    public Job cancel(String id) {
        Job job = get(id);
        synchronized (job) {
            if (job.state().terminal()) {
                throw new ApiException(409, "任务已结束，无法取消");
            }
            job.markCancelled();
            Future<?> future = job.future();
            if (future != null) {
                future.cancel(true);
            }
            return job;
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

    /** 测试用：等待队列排空且当前任务结束。 */
    void awaitQuiescence(long timeout, TimeUnit unit) throws InterruptedException {
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

    /** 在 job 线程上执行一次运行；任何 Throwable 都收敛为终态，绝不让异常逃逸到执行器。 */
    private void execute(Job job) {
        if (!job.markRunning()) {
            return;
        }
        try {
            BenchmarkOptions options = merger.merge(job.request(), env, workingDir);
            List<RunOutcome> outcomes =
                    launcher.launch(
                            options,
                            dir -> job.setSessionName(dir.getFileName().toString()));
            job.complete(outcomes);
        } catch (Throwable failure) {
            if (job.state() == JobState.CANCELLED || Thread.currentThread().isInterrupted()) {
                job.markCancelled();
            } else {
                job.fail(failure);
            }
        }
    }
}
```

- [ ] **Step 5: 跑全量测试确认两个任务类都绿**

Run: `mvn -o -pl ecbench-app test -Dtest='JobServiceTest,ModelKeyGuardTest'`
Expected: PASS，10 + 10 tests。

- [ ] **Step 6: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/job/ \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiException.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/
git commit -m "feat(app): serial job queue with best-effort cancellation"
```

---

### Task 8: `JobService` 的进度推导与 `JobView` 序列化

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobService.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobProgressTest.java`（新建）

**Interfaces:**
- Consumes: Task 7 的 `JobService` / `Job`；`RunDirectory.balanceCsv(int)` / `.analysisJson(int)` 的**命名规则**（`balance/run_<i>_daily_balance.csv`、`metrics/run_<i>_analysis.json`）。
- Produces: `ArrayNode jobsNode()`、`ObjectNode jobNode(Job job)`、`Path logDir()`。`JobView` 的 JSON 形状见 spec §4.4，Task 12/13 的路由层与 Task 17 的前端都依赖它。

**为什么进度从磁盘推导**：`ecbench-agent` 零中断检查、也没有任何进度回调接缝；侵入 agent 加回调的成本与风险都远高于「数已落盘的 CSV 行数」。`balance.csv` 每模拟日追加一行，`analysis.json` 每个 run 结束时写一个，两者都是既有产物，无需新增文件。

- [ ] **Step 1: 写 `JobProgressTest`（失败测试）**

```java
package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 进度从已落盘产物推导：balance.csv 行数（去表头）取各 run 最大值，analysis.json 个数即完成 run 数。 */
class JobProgressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.shutdown();
        }
    }

    /** logDir 指向 tempDir/log 的服务端参数。 */
    private static RunOptions server(Path logDir) {
        return new JobRequest("unused", 1, 1, 1, 1.0, 1.0, 1, 1, null,
                null, logDir.toString(), null, null);
    }

    private JobService serviceWith(Path workingDir, Path logDir, RunLauncher launcher) {
        service = new JobService(launcher, new ModelKeyGuard(null, Map.of(), MAPPER),
                server(logDir), Map.of(), workingDir, MAPPER);
        return service;
    }

    private static void writeBalance(Path session, int run, int dataRows) throws Exception {
        Path file = session.resolve("balance/run_" + run + "_daily_balance.csv");
        Files.createDirectories(file.getParent());
        StringBuilder csv = new StringBuilder("date,total_balance\n");
        for (int i = 0; i < dataRows; i++) {
            csv.append("2026-01-0").append(i + 1).append(",100000.00\n");
        }
        Files.writeString(file, csv.toString());
    }

    private static void writeAnalysis(Path session, int run) throws Exception {
        Path file = session.resolve("metrics/run_" + run + "_analysis.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
    }

    @Test
    void daysComeFromBalanceCsvRowCount(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        Path session = logDir.resolve("20261014_100001_gpt");
        writeBalance(session, 0, 7);
        JobService svc = serviceWith(workingDir, logDir, (options, onSession) -> List.of());

        Job job = svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\",\"maxDays\":30}"));
        job.setSessionName(session.getFileName().toString());

        JsonNode progress = svc.jobNode(job).get("progress");
        assertThat(progress.get("days").asInt()).isEqualTo(7);
        assertThat(progress.get("maxDays").asInt()).isEqualTo(30);
        assertThat(progress.get("runsTotal").asInt()).isEqualTo(1);
        assertThat(progress.get("runsDone").asInt()).isEqualTo(0);
    }

    @Test
    void daysTakeTheMaxAcrossRuns(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        Path session = logDir.resolve("20261014_100002_gpt");
        writeBalance(session, 0, 3);
        writeBalance(session, 1, 9);
        JobService svc = serviceWith(workingDir, logDir, (options, onSession) -> List.of());

        Job job = svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\",\"runs\":2}"));
        job.setSessionName(session.getFileName().toString());

        assertThat(svc.jobNode(job).get("progress").get("days").asInt()).isEqualTo(9);
    }

    @Test
    void runsDoneCountsAnalysisFiles(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        Path session = logDir.resolve("20261014_100003_gpt");
        writeAnalysis(session, 0);
        writeAnalysis(session, 1);
        JobService svc = serviceWith(workingDir, logDir, (options, onSession) -> List.of());

        Job job = svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\",\"runs\":3}"));
        job.setSessionName(session.getFileName().toString());

        JsonNode progress = svc.jobNode(job).get("progress");
        assertThat(progress.get("runsDone").asInt()).isEqualTo(2);
        assertThat(progress.get("runsTotal").asInt()).isEqualTo(3);
    }

    @Test
    void missingSessionOrFilesDegradeToZeros(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        JobService svc = serviceWith(workingDir, logDir, (options, onSession) -> List.of());
        Job queued = svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\"}"));

        JsonNode progress = svc.jobNode(queued).get("progress");
        assertThat(progress.get("days").asInt()).isZero();
        assertThat(progress.get("runsDone").asInt()).isZero();
        assertThat(progress.get("runsTotal").asInt()).isEqualTo(1);
    }

    @Test
    void partialCsvWithoutTrailingNewlineIsStillCounted(
            @TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        Path session = logDir.resolve("20261014_100004_gpt");
        Path file = session.resolve("balance/run_0_daily_balance.csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "date,total_balance\n2026-01-01,1.00\n2026-01-02,2.00");
        JobService svc = serviceWith(workingDir, logDir, (options, onSession) -> List.of());

        Job job = svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\"}"));
        job.setSessionName(session.getFileName().toString());

        assertThat(svc.jobNode(job).get("progress").get("days").asInt()).isEqualTo(2);
    }

    @Test
    void jobViewCarriesTheDocumentedShape(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        JobService svc = serviceWith(workingDir, logDir,
                (options, onSession) -> List.of(new RunOutcome(0, null, null)));

        Job job = svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\",\"maxDays\":5,\"seed\":7}"));
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        JsonNode view = svc.jobNode(job);
        assertThat(view.get("id").asText()).isEqualTo(job.id());
        assertThat(view.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(view.get("model").asText()).isEqualTo("gpt");
        assertThat(view.get("submittedAt").asText()).isNotEmpty();
        assertThat(view.has("startedAt")).isTrue();
        assertThat(view.get("request").get("maxDays").asInt()).isEqualTo(5);
        assertThat(view.get("request").get("seed").asLong()).isEqualTo(7L);
        assertThat(view.get("outcomes")).hasSize(1);
        assertThat(view.get("error").isNull()).isTrue();
        assertThat(view.get("sessionName").isNull()).isTrue();
    }

    @Test
    void jobsNodeListsEveryJob(@TempDir Path workingDir, @TempDir Path logDir) throws Exception {
        JobService svc = serviceWith(workingDir, logDir, (options, onSession) -> List.of());
        svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\"}"));
        svc.submit((ObjectNode) MAPPER.readTree("{\"model\":\"gpt\"}"));
        svc.awaitQuiescence(5, TimeUnit.SECONDS);

        assertThat(svc.jobsNode()).hasSize(2);
    }
}
```

`missingSessionOrFilesDegradeToZeros` 里提交后**不等任务结束**就读 `jobNode`，因此 `sessionName` 仍为 null（假 launcher 从不调回调），进度必须全 0 且不抛异常——这正是 QUEUED 阶段的真实形状。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=JobProgressTest`
Expected: 编译失败 —— `jobNode` / `jobsNode` 不存在。

- [ ] **Step 3: 在 `JobService` 追加进度推导与序列化**

在 `JobService` 类内追加（import 区补 `com.fasterxml.jackson.databind.node.ArrayNode`、`com.fasterxml.jackson.databind.node.ObjectNode`、`java.io.IOException`、`java.nio.file.Files`、`java.util.regex.Matcher`、`java.util.regex.Pattern`、`java.util.stream.Stream`）：

```java
    /** 会话根目录：serverDefaults.logDir() 为 null 时回退 workingDir/log，与 RunComponentFactory 同口径。 */
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

    /** 单个任务的 JobView（spec §4.4 的 JSON 形状）。 */
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
        node.set("outcomes", outcomesNode(job));
        putText(node, "error", job.error());
        return node;
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
            max = Math.max(max, countDataRows(session.resolve("balance/run_" + run + "_daily_balance.csv")));
        }
        return max;
    }

    /** 数据行数 = 非空行数 - 1（表头），下限 0。写一半的文件（无尾换行）也算最后一行。 */
    private static int countDataRows(Path csv) {
        if (!Files.isRegularFile(csv)) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(csv, java.nio.charset.StandardCharsets.UTF_8);
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
        Pattern pattern = Pattern.compile("run_(\\d+)_analysis\\.json");
        try (Stream<Path> files = Files.list(metrics)) {
            return (int) files.map(file -> file.getFileName().toString())
                    .map(pattern::matcher)
                    .filter(Matcher::matches)
                    .count();
        } catch (IOException | RuntimeException exception) {
            return 0;
        }
    }

    /** 终态且有结果时输出各 run 的 index / canonicalReason / finalDay，否则 JSON null。 */
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

    /** outcomes 为 null 时写 JSON null，保持前端形状稳定。 */
    private void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private void putInstant(ObjectNode node, String field, java.time.Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, String.valueOf(value));
        }
    }
```

**注意**：`node.set("outcomes", outcomesNode(job))` 中 `outcomesNode` 可能返回 `null`，而 `ObjectNode.set(field, null)` 在 Jackson 里等价于 `putNull` —— 但这是隐式行为，**改成显式**：

```java
        ArrayNode outcomes = outcomesNode(job);
        if (outcomes == null) {
            node.putNull("outcomes");
        } else {
            node.set("outcomes", outcomes);
        }
```

`RunOutcome.result()` 的实际访问器名以 `io.github.ecommercebench.agent.RunResult` 为准（`BenchmarkCommand` 里已用 `outcome.result().canonicalReason()` 与 `.finalDay()`，照抄即可）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=JobProgressTest`
Expected: PASS，7 tests。

- [ ] **Step 5: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/JobProgressTest.java
git commit -m "feat(app): derive job progress from on-disk artifacts and serialize JobView"
```

---

### Task 9: `BenchmarkRunLauncher` —— 每 job 新建 factory

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/job/BenchmarkRunLauncher.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/job/BenchmarkRunLauncherTest.java`

**Interfaces:**
- Consumes: `RunComponentFactory(ObjectMapper, LlmClientProvider, Consumer<Path>)`（Task 4）；既有 `RunCoordinator(RunComponentFactory)` / `.run(BenchmarkOptions)`；`RunOutcome`。
- Produces: `BenchmarkRunLauncher(ObjectMapper mapper, LlmClientProvider provider) implements RunLauncher`。Task 14 的 `DashboardLauncher` 用它作生产实现。

**本任务是约束 ① 的落地点**：`RunComponentFactory` 把 `catalog`/`modelRegistry`/`tokenCounter`/**`runDirectory`**/`fileJobs` 缓存成实例字段且「第一个 options 胜出」，所以**每次 `launch` 必须 `new` 一个**，绝不能复用 Spring 单例——否则第二个 job 的产物会写进第一个 job 的会话目录。

- [ ] **Step 1: 写失败测试**

用一个「记录被调用次数、并在调用时抛出以便提前返回」的 `RunComponentFactory` 子类不可行（`launch` 内部直接 new），因此本任务的测试**验证可观测的副作用**：`launch` 两次会创建两个不同的会话目录。用 `maxDays=1` + 无 LLM 会失败，所以改为**只验证构造与回调透传**，用一个必然失败的 options 让 `launch` 在装配阶段抛异常，同时断言异常前回调已触发或 factory 未被复用。

更稳的做法：把 factory 的构造抽成可覆盖的包级方法，测试用子类拦截。

```java
package io.github.ecommercebench.app.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** 每次 launch 必须新建一个 RunComponentFactory，否则缓存的会话目录会串到下一个 job。 */
class BenchmarkRunLauncherTest {

    private static final LlmClientProvider NO_PROVIDER = config -> null;

    /** 拦截 factory 构造：记录实例个数，并让 launch 立刻返回，避免真跑仿真。 */
    private static final class RecordingLauncher extends BenchmarkRunLauncher {
        private final List<RunComponentFactory> created = new ArrayList<>();
        private final List<Consumer<Path>> callbacks = new ArrayList<>();

        RecordingLauncher(ObjectMapper mapper, LlmClientProvider provider) {
            super(mapper, provider);
        }

        @Override
        RunComponentFactory newFactory(Consumer<Path> onSessionCreated) {
            RunComponentFactory factory = new RunComponentFactory(mapper(), provider(), onSessionCreated);
            created.add(factory);
            callbacks.add(onSessionCreated);
            return factory;
        }

        @Override
        List<RunOutcome> coordinate(RunComponentFactory factory, BenchmarkOptions options) {
            return List.of(new RunOutcome(0, null, null));
        }
    }

    @Test
    void eachLaunchBuildsItsOwnFactory() {
        RecordingLauncher launcher = new RecordingLauncher(new ObjectMapper(), NO_PROVIDER);

        launcher.launch(null, path -> {});
        launcher.launch(null, path -> {});

        assertThat(launcher.created).hasSize(2);
        assertThat(launcher.created.get(0)).isNotSameAs(launcher.created.get(1));
    }

    @Test
    void sessionCallbackIsForwardedToTheFactory() {
        RecordingLauncher launcher = new RecordingLauncher(new ObjectMapper(), NO_PROVIDER);
        List<Path> seen = new ArrayList<>();

        launcher.launch(null, seen::add);
        launcher.callbacks.get(0).accept(Path.of("/log/20261014_100001_gpt"));

        assertThat(seen).containsExactly(Path.of("/log/20261014_100001_gpt"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=BenchmarkRunLauncherTest`
Expected: 编译失败 —— `BenchmarkRunLauncher` 不存在。

- [ ] **Step 3: 实现 `BenchmarkRunLauncher`**

```java
package io.github.ecommercebench.app.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.BenchmarkOptions;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.run.RunComponentFactory;
import io.github.ecommercebench.app.run.RunCoordinator;
import io.github.ecommercebench.app.run.RunOutcome;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link RunLauncher} 的生产实现。
 *
 * <p>**每次 launch 都新建一个 {@link RunComponentFactory}**。这不是浪费：该工厂把 catalog、models 注册表、
 * tokenizer、尤其是**会话目录**缓存成实例字段且「第一个 options 胜出」，复用一个实例跑第二个 job 会让产物
 * 写进第一个 job 的会话目录，并静默沿用第一个 job 的 data-dir / models_config / tokenizer 路径。
 *
 * <p>相较之下 {@link LlmClientProvider} 是无状态且线程安全的（既有 {@code RunCoordinator} 已并发调用同一实例的
 * {@code create}），因此跨 job 复用，不重建。
 *
 * <p>{@code newFactory} 与 {@code coordinate} 为包级可覆盖接缝，使测试无需真跑仿真即可验证「不串味」这条不变量。
 */
public class BenchmarkRunLauncher implements RunLauncher {

    private final ObjectMapper mapper;
    private final LlmClientProvider provider;

    public BenchmarkRunLauncher(ObjectMapper mapper, LlmClientProvider provider) {
        this.mapper = mapper;
        this.provider = provider;
    }

    @Override
    public List<RunOutcome> launch(BenchmarkOptions options, Consumer<Path> onSessionCreated) {
        return coordinate(newFactory(onSessionCreated), options);
    }

    /** 供测试拦截：生产实现新建一个携带会话回调的工厂。 */
    RunComponentFactory newFactory(Consumer<Path> onSessionCreated) {
        return new RunComponentFactory(mapper, provider, onSessionCreated);
    }

    /** 供测试拦截：生产实现走既有的并行编排器。 */
    List<RunOutcome> coordinate(RunComponentFactory factory, BenchmarkOptions options) {
        return new RunCoordinator(factory).run(options);
    }

    ObjectMapper mapper() {
        return mapper;
    }

    LlmClientProvider provider() {
        return provider;
    }
}
```

注意类声明**不能是 `final`**（测试要子类化）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=BenchmarkRunLauncherTest`
Expected: PASS，2 tests。

- [ ] **Step 5: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/job/BenchmarkRunLauncher.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/job/BenchmarkRunLauncherTest.java
git commit -m "feat(app): launch each web job with a fresh component factory"
```

---

### Task 10: `ApiRouter` 改为按 (方法, 路径) 分发

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java:49-81`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java`

**Interfaces:**
- Consumes: `ApiException`（Task 7）；既有 `SessionScanner` / `CompareService` / `RunArtifactService`。
- Produces: `ApiRouter.handle` 的 catch 链新增 `ApiException → e.status()`；`route` 改为逐路径校验方法；包级静态 `requireGet(String method, String path)`。**本任务不新增端点**，只做分发骨架，让 Task 11/12/13 各自独立可评审。

- [ ] **Step 1: 写失败测试**

在 `DashboardServerTest` 末尾追加（`rejectsNonGetRequest` 保持原样不动）：

```java
    @Test
    void deleteOnReadOnlyEndpointIsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/compare"))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).contains("error");
    }

    @Test
    void postToUnknownEndpointIsNotFoundNotMethodNotAllowed() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/nope"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void putOnSessionArtifactIsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + server.port() + "/api/sessions/2026_qwen3.7-plus/analysis"))
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: FAIL —— 三个新测试都拿到 405 之外的码（当前 `handle` 对一切非 GET 先返 405，故 `postToUnknownEndpointIsNotFoundNotMethodNotAllowed` 会拿到 405 而非 404）。

- [ ] **Step 3: 改 `ApiRouter.handle` 的 catch 链**

把 `handle` 方法整体替换为（**移除**开头的「非 GET → 405」总闸，改由 `route` 逐路径判定；`ApiException` 分支必须排在 `RuntimeException` 之前）：

```java
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, error(String.valueOf(exception.getMessage())));
        } catch (ArtifactNotFoundException exception) {
            write(exchange, 404, error(String.valueOf(exception.getMessage())));
        } catch (ApiException exception) {
            write(exchange, exception.status(), error(String.valueOf(exception.getMessage())));
        } catch (RuntimeException exception) {
            write(exchange, 500, error("内部错误: " + exception.getClass().getSimpleName()));
        }
    }
```

- [ ] **Step 4: 改 `route` 为逐路径方法校验**

把 `route` 方法整体替换为：

```java
    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/sessions")) {
            requireGet(method, path);
            write(exchange, 200, sessionsNode());
            return;
        }
        if (path.equals("/api/compare")) {
            requireGet(method, path);
            write(exchange, 200, compareNode(query(exchange.getRequestURI())));
            return;
        }
        if (path.startsWith(PREFIX)) {
            requireGet(method, path);
            write(exchange, 200, runArtifact(path, query(exchange.getRequestURI())));
            return;
        }
        write(exchange, 404, error("未知端点: " + path));
    }

    /** 已知路径但不支持该方法 → 405（未知路径由 route 的兜底分支给 404，不进这里）。 */
    private static void requireGet(String method, String path) {
        if (!"GET".equals(method)) {
            throw new ApiException(405, "该端点仅支持 GET，收到 " + method + ": " + path);
        }
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: PASS，含既有 `rejectsNonGetRequest`（POST `/api/sessions` → 405）与三个新测试。

- [ ] **Step 6: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java
git commit -m "refactor(web): dispatch api routes by method and path, map ApiException status"
```

---

### Task 11: `ModelCatalogService` 与 `GET /api/models`

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ModelCatalogService.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java:31-46`
- Modify: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/ModelCatalogServiceTest.java`

**Interfaces:**
- Consumes: `ModelKeyGuard.registry() / missingEnv(String) / npcMissingEnv()`（Task 6）；`ModelConfig.key() / provider() / modelName() / effort()`。
- Produces:
  - `ModelCatalogService(ModelKeyGuard keys, ObjectMapper mapper)`；`ObjectNode node()`
  - `ApiRouter` 构造签名追加 `ModelCatalogService models`
  - `DashboardServer` 构造签名追加 `ModelCatalogService models`
  - JSON 形状见 spec §4.4

- [ ] **Step 1: 写 `ModelCatalogServiceTest`**

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.job.ModelKeyGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** /api/models 的 JSON 形状与 keyReady 判定，含 NPC 段独立返回。 */
class ModelCatalogServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG =
            """
                    {
                      "npc_tools": {"provider": "openai", "model": "gpt-4o-mini"},
                      "models": {
                        "gpt": {"provider": "openai", "model_name": "gpt-x", "effort": "max"},
                        "qwen": {"provider": "openai-compatible", "model_name": "qwen-x",
                                 "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                 "api_key": "${DASHSCOPE_API_KEY}"}
                      }
                    }
                    """;

    private static ModelCatalogService service(Path dir, Map<String, String> env) throws Exception {
        Files.writeString(dir.resolve("models_config.json"), CONFIG);
        Path config = dir.resolve("models_config.json");
        return new ModelCatalogService(new ModelKeyGuard(config, env, MAPPER), MAPPER);
    }

    @Test
    void listsEveryModelKeyWithReadiness(@TempDir Path dir) throws Exception {
        JsonNode node = service(dir, Map.of("DASHSCOPE_API_KEY", "sk-y")).node();

        assertThat(node.get("models")).hasSize(2);
        JsonNode qwen = findByKey(node.get("models"), "qwen");
        assertThat(qwen.get("keyReady").asBoolean()).isTrue();
        assertThat(qwen.get("missingEnv").isNull()).isTrue();
        assertThat(qwen.get("provider").asText()).isEqualTo("openai-compatible");
        assertThat(qwen.get("modelName").asText()).isEqualTo("qwen-x");
        JsonNode gpt = findByKey(node.get("models"), "gpt");
        assertThat(gpt.get("keyReady").asBoolean()).isFalse();
        assertThat(gpt.get("missingEnv").asText()).isEqualTo("OPENAI_API_KEY");
        assertThat(gpt.get("effort").asText()).isEqualTo("max");
    }

    @Test
    void npcSectionIsAlwaysPresent(@TempDir Path dir) throws Exception {
        JsonNode node = service(dir, Map.of("DASHSCOPE_API_KEY", "sk-y")).node();

        assertThat(node.get("npc").get("modelName").asText()).isEqualTo("gpt-4o-mini");
        assertThat(node.get("npc").get("keyReady").asBoolean()).isFalse();
        assertThat(node.get("npc").get("missingEnv").asText()).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void nullEffortIsWrittenAsJsonNull(@TempDir Path dir) throws Exception {
        JsonNode node = service(dir, Map.of()).node();

        assertThat(findByKey(node.get("models"), "qwen").get("effort").isNull()).isTrue();
    }

    private static JsonNode findByKey(JsonNode models, String key) {
        for (JsonNode model : models) {
            if (model.get("key").asText().equals(key)) {
                return model;
            }
        }
        throw new AssertionError("未找到模型键 " + key);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=ModelCatalogServiceTest`
Expected: 编译失败 —— `ModelCatalogService` 不存在。

- [ ] **Step 3: 实现 `ModelCatalogService`**

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.app.job.ModelKeyGuard;
import io.github.ecommercebench.domain.config.ModelConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 产出 {@code GET /api/models} 的 JSON：全部主模型键 + 各自的密钥就绪状态，外加独立的 NPC 段。
 *
 * <p>就绪判定完全委托 {@link ModelKeyGuard}，本类只做序列化，不另写一套推断规则。
 *
 * <p>NPC 段必须独立返回：{@code RunComponentFactory} 每个 run 都会额外为 {@code npcModel} 建客户端，
 * 因此即便主模型选的是 qwen，缺 {@code OPENAI_API_KEY} 一样跑不起来——前端据此单独告警。
 */
public final class ModelCatalogService {

    private final ModelKeyGuard keys;
    private final ObjectMapper mapper;

    public ModelCatalogService(ModelKeyGuard keys, ObjectMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    /** {@code {models:[…], npc:{…}}}，models 按 key 升序以便前端下拉稳定。 */
    public ObjectNode node() {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode models = root.putArray("models");
        List<ModelConfig> configs = new ArrayList<>(keys.registry().models().values());
        configs.sort(Comparator.comparing(ModelConfig::key));
        for (ModelConfig config : configs) {
            models.add(modelNode(config, keys.missingEnv(config.key())));
        }
        root.set("npc", modelNode(keys.registry().npcModel(), keys.npcMissingEnv()));
        return root;
    }

    private ObjectNode modelNode(ModelConfig config, String missingEnv) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", config.key());
        node.put("provider", config.provider());
        node.put("modelName", config.modelName());
        if (config.effort() == null) {
            node.putNull("effort");
        } else {
            node.put("effort", config.effort());
        }
        node.put("keyReady", missingEnv == null);
        if (missingEnv == null) {
            node.putNull("missingEnv");
        } else {
            node.put("missingEnv", missingEnv);
        }
        return node;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=ModelCatalogServiceTest`
Expected: PASS，3 tests。

- [ ] **Step 5: 把端点接进 `ApiRouter`**

`ApiRouter` 构造追加一个参数并加字段：

```java
    private final ModelCatalogService models;

    public ApiRouter(
            ObjectMapper mapper,
            SessionScanner scanner,
            CompareService compare,
            RunArtifactService artifacts,
            ModelCatalogService models) {
        this.mapper = mapper;
        this.scanner = scanner;
        this.compare = compare;
        this.artifacts = artifacts;
        this.models = models;
    }
```

在 `route` 里、`path.startsWith(PREFIX)` 分支**之前**插入：

```java
        if (path.equals("/api/models")) {
            requireGet(method, path);
            write(exchange, 200, models.node());
            return;
        }
```

- [ ] **Step 6: 改 `DashboardServer` 构造并更新既有调用点**

> **给实施者的提示**：本步往 `ServeCommand` 里写的装配代码是**临时的**——Task 12 会给它加上 `JobService`，Task 14 会把整段搬进 `DashboardLauncher` 并把 `ServeCommand` 瘦成三行。不要在这里抽助手方法或做额外打磨，能编译、能跑通测试即可。

`DashboardServer` 的构造签名追加 `ModelCatalogService models`，并把它传给 `ApiRouter`：

```java
    public DashboardServer(
            Path logDir,
            String host,
            int port,
            Map<String, RosterEntry> roster,
            ModelCatalogService models)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ApiRouter api =
                new ApiRouter(
                        mapper,
                        new SessionScanner(logDir),
                        new CompareService(mapper),
                        new RunArtifactService(mapper, roster),
                        models);
        // 先绑定端口再建线程池：绑定失败（如端口被占）时不会泄漏已创建的工作线程。
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS);
        this.httpServer.createContext("/api", api);
        this.httpServer.createContext("/", new StaticAssets());
        this.httpServer.setExecutor(this.executor);
    }
```

**删掉既有的 3 参与 4 参构造**（全仓只有 2 个调用点：`ServeCommand:53`、`DashboardServerTest:39`）。本任务先把这两处改成传 `null` 占位以恢复编译，Task 14 会换成真实现；若你更倾向一次到位，可在本任务就把 `ServeCommand` 改为构造一个真的 `ModelCatalogService`（用 `ResourcePaths.modelsConfig(null)` + `EffectiveEnvironment.load().asMap()`）。**选后者**，避免留下 null 占位：

```java
        ModelCatalogService models =
                new ModelCatalogService(
                        new ModelKeyGuard(
                                ResourcePaths.of(Path.of(System.getProperty("user.dir")))
                                        .modelsConfig(null),
                                EffectiveEnvironment.load().asMap(),
                                new ObjectMapper()),
                        new ObjectMapper());
        try (DashboardServer server = new DashboardServer(root, host, port, roster, models)) {
```

- [ ] **Step 7: 更新 `DashboardServerTest` 的 fixture**

在 `startFixtureServer()` 里，`server = new DashboardServer(log, "127.0.0.1", 0);` 之前写入一份最小 models_config，并把构造改成 5 参：

```java
        Path configDir = log.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve("models_config.json"),
                "{\"npc_tools\":{\"provider\":\"openai\",\"model\":\"gpt-4o-mini\"},"
                        + "\"models\":{\"gpt\":{\"provider\":\"openai\",\"model_name\":\"gpt-x\"}}}");
        ModelCatalogService models =
                new ModelCatalogService(
                        new io.github.ecommercebench.app.job.ModelKeyGuard(
                                configDir.resolve("models_config.json"),
                                java.util.Map.of("OPENAI_API_KEY", "sk-test"),
                                new com.fasterxml.jackson.databind.ObjectMapper()),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        server = new DashboardServer(log, "127.0.0.1", 0, java.util.Map.of(), models);
```

**注意**：`config` 目录建在 `log` 下会被 `SessionScanner` 当成一个会话目录扫到（它只过滤 `.` 前缀）。**改为把 models_config 写到 `log` 之外的另一个 `@TempDir`**：给测试类加第二个 `@TempDir Path config;` 字段，写到 `config.resolve("models_config.json")`。

追加端点测试：

```java
    @Test
    void modelsEndpointReportsKeyReadiness() throws Exception {
        HttpResponse<String> response = get("/api/models");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"key\":\"gpt\"").contains("\"keyReady\":true");
        assertThat(response.body()).contains("\"npc\"");
    }

    @Test
    void modelsEndpointRejectsPost() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/models"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }
```

- [ ] **Step 8: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest='DashboardServerTest,ModelCatalogServiceTest'`
Expected: PASS。同时确认 `listsDiscoveredSessions` 仍只看到 1 个会话（验证 models_config 没有落在 `log` 下）。

- [ ] **Step 9: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ModelCatalogService.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/ModelCatalogServiceTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java
git commit -m "feat(web): expose model catalog with key readiness at /api/models"
```

---

### Task 12: `POST /api/jobs` —— Origin 校验、body 上限与提交

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java`
- Modify: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java`

**Interfaces:**
- Consumes: `JobService.submit(JsonNode) -> Job`、`Job.id() / state()`；`ApiException`。
- Produces:
  - `ApiRouter` 构造签名追加 `JobService jobs` 与 `IntSupplier portSupplier`
  - `DashboardServer` 构造签名追加 `JobService jobs`；`close()` 追加 `jobs.shutdown()`
  - 端点 `POST /api/jobs` → 202 `{"id":…,"state":"QUEUED"}`
  - 包级常量 `ApiRouter.MAX_BODY_BYTES = 65536`

- [ ] **Step 1: 写失败测试**

`DashboardServerTest` 追加一个字段与辅助方法，并加测试。先给类加：

```java
    @TempDir Path config;

    private io.github.ecommercebench.app.job.JobService jobService;
```

`startFixtureServer()` 里在构造 `server` 之前加：

```java
        jobService =
                new io.github.ecommercebench.app.job.JobService(
                        (options, onSession) -> java.util.List.of(),
                        new io.github.ecommercebench.app.job.ModelKeyGuard(
                                config.resolve("models_config.json"),
                                java.util.Map.of("OPENAI_API_KEY", "sk-test"),
                                new com.fasterxml.jackson.databind.ObjectMapper()),
                        new io.github.ecommercebench.app.cli.CliRunOptions(),
                        java.util.Map.of(),
                        java.nio.file.Path.of("."),
                        new com.fasterxml.jackson.databind.ObjectMapper());
```

并把 `server = new DashboardServer(...)` 改成 6 参：

```java
        server = new DashboardServer(log, "127.0.0.1", 0, java.util.Map.of(), models, jobService);
```

`stopServer()` 改为：

```java
    @AfterEach
    void stopServer() {
        server.close();
    }
```

（`DashboardServer.close()` 内部会调 `jobService.shutdown()`，测试不必重复调。）

追加辅助方法与测试：

```java
    private HttpResponse<String> post(String path, String body, String origin) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void submitsAJobAndReturnsAccepted() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "{\"model\":\"gpt\",\"maxDays\":2}", null);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"state\":\"QUEUED\"").contains("\"id\"");
    }

    @Test
    void rejectsMalformedJsonWithBadRequest() throws Exception {
        HttpResponse<String> response = post("/api/jobs", "not-json", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("not-json");
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
        HttpResponse<String> response =
                post("/api/jobs", "{\"model\":\"gpt\"}", "http://evil.example");

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

        HttpResponse<String> response = post("/api/jobs", huge, null);

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void rejectsQueueBeyondLimitWithConflict() throws Exception {
        // 用会阻塞的 launcher 占住执行线程，才能把队列堆到上限
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        io.github.ecommercebench.app.job.JobService blocking =
                new io.github.ecommercebench.app.job.JobService(
                        (options, onSession) -> {
                            try {
                                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return java.util.List.of();
                        },
                        new io.github.ecommercebench.app.job.ModelKeyGuard(
                                config.resolve("models_config.json"),
                                java.util.Map.of("OPENAI_API_KEY", "sk-test"),
                                new com.fasterxml.jackson.databind.ObjectMapper()),
                        new io.github.ecommercebench.app.cli.CliRunOptions(),
                        java.util.Map.of(),
                        java.nio.file.Path.of("."),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        try (DashboardServer blockingServer =
                new DashboardServer(log, "127.0.0.1", 0, java.util.Map.of(), models, blocking)) {
            blockingServer.start();
            for (int i = 0; i < io.github.ecommercebench.app.job.JobService.MAX_QUEUED; i++) {
                assertThat(postTo(blockingServer, "{\"model\":\"gpt\"}").statusCode()).isEqualTo(202);
            }

            assertThat(postTo(blockingServer, "{\"model\":\"gpt\"}").statusCode()).isEqualTo(409);
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
```

上面用到的 `models` 变量需提升为测试类字段（在 `startFixtureServer` 里赋值），以便 `rejectsQueueBeyondLimitWithConflict` 复用。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: 编译失败 —— `DashboardServer` 6 参构造不存在。

- [ ] **Step 3: `DashboardServer` 注入 `JobService` 与端口供给**

把构造改为（`java.util.function.IntSupplier` 需 import）：

```java
    public DashboardServer(
            Path logDir,
            String host,
            int port,
            Map<String, RosterEntry> roster,
            ModelCatalogService models,
            JobService jobs)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        // 先绑定端口再建线程池与路由：ApiRouter 的 Origin 校验需要读实际绑定端口。
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS);
        ApiRouter api =
                new ApiRouter(
                        mapper,
                        new SessionScanner(logDir),
                        new CompareService(mapper),
                        new RunArtifactService(mapper, roster),
                        models,
                        jobs,
                        () -> httpServer.getAddress().getPort());
        this.httpServer.createContext("/api", api);
        this.httpServer.createContext("/", new StaticAssets());
        this.httpServer.setExecutor(this.executor);
        this.jobs = jobs;
    }
```

加字段 `private final JobService jobs;`，并把 `close()` 改为：

```java
    /** 停止监听、关闭 HTTP 工作线程池，并中断仍在跑的任务。 */
    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
        jobs.shutdown();
    }
```

import `io.github.ecommercebench.app.job.JobService`。

- [ ] **Step 4: `ApiRouter` 加 POST 分支、Origin 校验与 body 上限**

构造追加两个参数与字段：

```java
    /** POST body 的字节上限，超出即 413。 */
    static final int MAX_BODY_BYTES = 65_536;

    private final JobService jobs;
    private final IntSupplier portSupplier;
```

`route` 里在 `/api/models` 分支之后、`path.startsWith(PREFIX)` 之前插入：

```java
        if (path.equals("/api/jobs")) {
            if ("POST".equals(method)) {
                requireSameOrigin(exchange);
                Job job = jobs.submit(readBody(exchange));
                ObjectNode accepted = mapper.createObjectNode();
                accepted.put("id", job.id());
                accepted.put("state", job.state().name());
                write(exchange, 202, accepted);
                return;
            }
            requireGet(method, path);
            write(exchange, 200, jobs.jobsNode());
            return;
        }
```

（`write` 目前只接受 `JsonNode`，`ObjectNode` 是其子类型，可直接传。）

追加三个私有方法：

```java
    /**
     * 跨站防护：仅对写侧方法生效。
     *
     * <p>比对基准用**服务端已知的绑定端口**，不用请求的 {@code Host} 头（后者可被 DNS rebinding 篡改）。
     * 服务虽只绑 127.0.0.1，但浏览器里任意网页都能向它发 POST；不挡的话任何恶意页面都能拿用户的 API key
     * 跑几小时的任务。缺失 Origin 视为非浏览器客户端（curl），放行。
     */
    private void requireSameOrigin(HttpExchange exchange) {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        if (origins == null || origins.isEmpty()) {
            return;
        }
        String origin = origins.get(0);
        int port = portSupplier.getAsInt();
        if (origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port)) {
            return;
        }
        throw new ApiException(403, "跨域请求被拒绝");
    }

    /**
     * 读取并解析 POST body：边读边计数，超上限立即 413。
     *
     * <p>无论成功失败都要把请求体读干或关闭，否则 JDK HttpServer 会挂起该连接。
     * 解析失败的错误文案**不回显原始 body**，避免反射型内容注入。
     */
    private JsonNode readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    drain(in);
                    throw new ApiException(413, "请求体超过上限 " + MAX_BODY_BYTES + " 字节");
                }
                buffer.write(chunk, 0, read);
            }
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length == 0) {
            throw new IllegalArgumentException("请求体为空");
        }
        try {
            return mapper.readTree(bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("请求体不是合法 JSON");
        }
    }

    /** 丢弃剩余请求体，让连接可以被复用而不是挂起。 */
    private static void drain(InputStream in) {
        try {
            byte[] sink = new byte[8192];
            while (in.read(sink) != -1) {
                // 逐块丢弃
            }
        } catch (IOException ignored) {
            // 读干失败无妨：连接随后会被服务端关闭
        }
    }
```

import 追加：`com.fasterxml.jackson.databind.node.ObjectNode`、`io.github.ecommercebench.app.job.Job`、`io.github.ecommercebench.app.job.JobService`、`java.io.ByteArrayOutputStream`、`java.io.InputStream`、`java.util.function.IntSupplier`。

- [ ] **Step 5: `ServeCommand` 装配 `JobService`**

> 同 Task 11：这段装配代码在 Task 14 会整体搬进 `DashboardLauncher`。保持最小可编译，不要提前抽层。

在 Task 11 已加的 `models` 构造之后追加：

```java
        ObjectMapper mapper = new ObjectMapper();
        EffectiveEnvironment environment = EffectiveEnvironment.load();
        Path workingDir = Path.of(System.getProperty("user.dir"));
        ModelKeyGuard keys =
                new ModelKeyGuard(
                        ResourcePaths.of(workingDir).modelsConfig(environment.asMap().get("ECBENCH_MODELS_CONFIG")),
                        environment.asMap(),
                        mapper);
        ModelCatalogService models = new ModelCatalogService(keys, mapper);
        LlmClientProvider provider = LlmClientProviders.fromEnvironment(environment.asMap(), mapper);
        JobService jobs =
                new JobService(
                        new BenchmarkRunLauncher(mapper, provider),
                        keys,
                        new CliRunOptions(),
                        environment.asMap(),
                        workingDir,
                        mapper);
        try (DashboardServer server = new DashboardServer(root, host, port, roster, models, jobs)) {
```

`new CliRunOptions()` 作为服务端默认参数：4 个路径类字段全为 null，于是 `JobService.logDir()` 回退到 `workingDir/log`，与 `serve --log-dir` 的缺省一致。（Task 14 会把它换成真正携带 `--log-dir`/`--data-dir` 的服务端参数对象。）

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: PASS，含 9 个新测试与全部既有测试。

- [ ] **Step 7: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardServer.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java
git commit -m "feat(web): accept run submissions at POST /api/jobs with origin and size guards"
```

---

### Task 13: `GET /api/jobs`、`GET /api/jobs/{id}`、`DELETE /api/jobs/{id}`

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java`
- Modify: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java`

**Interfaces:**
- Consumes: `JobService.jobsNode() / jobNode(Job) / get(String) / cancel(String)`（Task 7/8）。
- Produces: 三个端点。Task 17 的前端轮询依赖 `GET /api/jobs` 的 `{"jobs":[JobView…]}` 形状与 `DELETE` 的 `{"id":…,"state":"CANCELLED"}`。

- [ ] **Step 1: 写失败测试**

`DashboardServerTest` 追加：

```java
    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if ("DELETE".equals(method)) {
            builder.DELETE();
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void listsSubmittedJobs() throws Exception {
        post("/api/jobs", "{\"model\":\"gpt\"}", null);

        HttpResponse<String> response = send("GET", "/api/jobs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"jobs\"").contains("\"model\":\"gpt\"");
    }

    @Test
    void jobViewCarriesProgressAndRequest() throws Exception {
        String id = MAPPER.readTree(post("/api/jobs", "{\"model\":\"gpt\",\"maxDays\":9}", null).body())
                .get("id").asText();
        jobService.awaitQuiescenceForTest(5, java.util.concurrent.TimeUnit.SECONDS);

        String body = send("GET", "/api/jobs/" + id).body();
        JsonNode view = MAPPER.readTree(body);

        assertThat(view.get("id").asText()).isEqualTo(id);
        assertThat(view.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(view.get("request").get("maxDays").asInt()).isEqualTo(9);
        assertThat(view.get("progress").get("maxDays").asInt()).isEqualTo(9);
        assertThat(view.get("progress").get("days").asInt()).isZero();
        assertThat(view.get("sessionName").isNull()).isTrue();
        assertThat(view.get("outcomes")).hasSize(1);
    }

    @Test
    void unknownJobIdIsNotFound() throws Exception {
        assertThat(send("GET", "/api/jobs/absent").statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/jobs/absent").statusCode()).isEqualTo(404);
    }

    @Test
    void deleteFinishedJobIsConflict() throws Exception {
        String id = MAPPER.readTree(post("/api/jobs", "{\"model\":\"gpt\"}", null).body())
                .get("id").asText();
        jobService.awaitQuiescenceForTest(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(send("DELETE", "/api/jobs/" + id).statusCode()).isEqualTo(409);
    }

    @Test
    void postToSingleJobEndpointIsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/jobs/any"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void deleteWithoutOriginHeaderIsAllowed() throws Exception {
        String id = MAPPER.readTree(post("/api/jobs", "{\"model\":\"gpt\"}", null).body())
                .get("id").asText();
        jobService.awaitQuiescenceForTest(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(send("DELETE", "/api/jobs/" + id).statusCode()).isEqualTo(409);
    }
```

给测试类加一个静态 mapper 字段：

```java
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
```

`awaitQuiescence` 在 Task 7 里是**包级**方法，测试在 `app.web` 包访问不到 `app.job` 的包级成员。**在 `JobService` 上加一个 public 包装**：

```java
    /** 等待全部任务到达终态；供测试与冒烟脚本使用。 */
    public void awaitQuiescenceForTest(long timeout, TimeUnit unit) throws InterruptedException {
        awaitQuiescence(timeout, unit);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: FAIL —— `GET /api/jobs` 目前走 Task 12 的分支能通，但 `GET /api/jobs/{id}` 与 `DELETE` 都落到 404 兜底。

- [ ] **Step 3: 加 `/api/jobs/{id}` 分支**

在 `route` 里 `/api/jobs` 精确匹配分支**之后**插入：

```java
        if (path.startsWith(JOB_PREFIX)) {
            String id = path.substring(JOB_PREFIX.length());
            if (id.isEmpty() || id.contains("/")) {
                throw new IllegalArgumentException("非法任务 id: " + id);
            }
            if ("GET".equals(method)) {
                write(exchange, 200, jobs.jobNode(jobs.get(id)));
                return;
            }
            if ("DELETE".equals(method)) {
                requireSameOrigin(exchange);
                Job cancelled = jobs.cancel(id);
                ObjectNode node = mapper.createObjectNode();
                node.put("id", cancelled.id());
                node.put("state", cancelled.state().name());
                write(exchange, 200, node);
                return;
            }
            requireGet(method, path);
            return;
        }
```

加常量（放在既有 `PREFIX` 旁）：

```java
    private static final String JOB_PREFIX = "/api/jobs/";
```

**注意分支顺序**：`path.equals("/api/jobs")` 必须在 `path.startsWith(JOB_PREFIX)` 之前判定——`"/api/jobs"` 不满足 `startsWith("/api/jobs/")`，所以两者其实互斥，但保持先精确后前缀的既有风格。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=DashboardServerTest`
Expected: PASS。

- [ ] **Step 5: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。
Run: `grep -c 'label' ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RunComparator.java` → 确认 `METRIC_DEFS` 未被触碰。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/web/ApiRouter.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/job/JobService.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardServerTest.java
git commit -m "feat(web): list, inspect and cancel submitted jobs over the api"
```

---

### Task 14: `ServeOptions`、`DashboardLauncher` 与 `ServeCommand` 瘦身

**Files:**
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeOptions.java`
- Create: `ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardLauncher.java`
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java`（大幅瘦身）
- Rename: `ecbench-app/src/test/java/io/github/ecommercebench/app/cli/ServeCommandCatalogTest.java` → `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardLauncherPathTest.java`
- Test: `ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardLauncherTest.java`（新建）

**Interfaces:**
- Consumes: `JobService`、`BenchmarkRunLauncher`、`ModelKeyGuard`、`ModelCatalogService`、`DashboardServer` 6 参构造、`EffectiveEnvironment.load()`、`LlmClientProviders.fromEnvironment`、`ResourcePaths.modelsConfig/optional`、`SupplierRosterReader.readByEmail`。
- Produces:
  - `ServeOptions`：`int port()`（默认 8080）、`String host()`（默认 `127.0.0.1`）、`String catalog()`（默认 null）
  - `DashboardLauncher.launch(String host, int port, String catalog, RunOptions serverDefaults) -> int`（**阻塞**，返回退出码；类与方法均非 final，供测试子类化）
  - 包级静态 `DashboardLauncher.resolveCatalog(String override, Path workingDir) -> Path`、`DashboardLauncher.resolveLogDir(String override, Path workingDir) -> Path`
- Task 15 的 `BenchmarkCommand` 依赖 `launch` 的这 4 个参数与顺序。

- [ ] **Step 1: 新建 `ServeOptions`**

```java
package io.github.ecommercebench.app.cli;

import picocli.CommandLine.Option;

/**
 * 起 Web 服务所需的三个参数，被根命令与 {@code serve} 子命令共享。
 *
 * <p>**刻意不含 {@code --log-dir}**：{@code CliRunOptions} 已声明同名选项，若两个 mixin 都带它，Picocli 会在
 * 命令注册时因重复声明直接抛异常。会话根目录统一由 {@code CliRunOptions.logDir()} 提供——写侧产物根与读侧会话根
 * 本来就是同一个目录。
 */
public final class ServeOptions {

    @Option(names = "--port", description = "监听端口，0 表示随机分配", defaultValue = "8080")
    private int port;

    @Option(names = "--host", description = "绑定地址，默认仅本地回环", defaultValue = "127.0.0.1")
    private String host;

    @Option(
            names = "--catalog",
            description = "供应商名册 CSV（用于 chatbox 欺诈标注），默认取资源根 data/suppliers.csv")
    private String catalog;

    public int port() {
        return port;
    }

    public String host() {
        return host;
    }

    public String catalog() {
        return catalog;
    }
}
```

- [ ] **Step 2: 迁移路径解析测试（先让它失败）**

```bash
git mv ecbench-app/src/test/java/io/github/ecommercebench/app/cli/ServeCommandCatalogTest.java \
       ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardLauncherPathTest.java
```

把该文件的 `package` 改为 `io.github.ecommercebench.app.web;`，类名改为 `DashboardLauncherPathTest`，并把三处 `ServeCommand.resolveCatalog(...)` 全部改为 `DashboardLauncher.resolveCatalog(...)`。**三条断言一字不改**——这是「行为不变」的证据。

再新建 `DashboardLauncherTest`：

```java
package io.github.ecommercebench.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ecommercebench.app.config.RunOptions;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 启动前置校验与参数透传：目录不存在返回 2，且不去构造任何服务。 */
class DashboardLauncherTest {

    private record Recorded(String host, int port, String catalog, RunOptions defaults, Path root) {}

    /** 记录参数并立即返回 0，绝不真的绑定端口或阻塞。 */
    private static final class RecordingLauncher extends DashboardLauncher {
        private Recorded recorded;

        @Override
        int start(String host, int port, String catalog, RunOptions serverDefaults, Path root, Path workingDir) {
            this.recorded = new Recorded(host, port, catalog, serverDefaults, root);
            return 0;
        }
    }

    @Test
    void missingLogDirectoryYieldsExitCodeTwo(@TempDir Path workingDir) {
        DashboardLauncher launcher = new DashboardLauncher();

        int exit = launcher.launch("127.0.0.1", 0, null, new StubDefaults("absent"), workingDir, "absent");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void existingLogDirectoryReachesStart(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("log"));
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = launcher.launch("127.0.0.1", 0, "roster.csv", new StubDefaults("log"), workingDir, null);

        assertThat(exit).isZero();
        assertThat(launcher.recorded.port()).isZero();
        assertThat(launcher.recorded.catalog()).isEqualTo("roster.csv");
        assertThat(launcher.recorded.defaults().logDir()).isEqualTo("log");
        assertThat(launcher.recorded.root()).isEqualTo(workingDir.resolve("log"));
    }

    @Test
    void logDirOverrideIsResolvedAgainstWorkingDir(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("elsewhere"));
        RecordingLauncher launcher = new RecordingLauncher();

        launcher.launch("127.0.0.1", 0, null, new StubDefaults("elsewhere"), workingDir, "elsewhere");

        assertThat(launcher.recorded.root()).isEqualTo(workingDir.resolve("elsewhere"));
        assertThat(DashboardLauncher.resolveLogDir("elsewhere", workingDir))
                .isEqualTo(workingDir.resolve("elsewhere"));
    }

    @Test
    void nullLogDirOverrideFallsBackToWorkingDirLog(@TempDir Path workingDir) throws Exception {
        Files.createDirectories(workingDir.resolve("log"));
        RecordingLauncher launcher = new RecordingLauncher();

        launcher.launch("127.0.0.1", 0, null, new StubDefaults(null), workingDir, null);

        assertThat(launcher.recorded.root()).isEqualTo(workingDir.resolve("log"));
    }

    /** 只提供 logDir 的最小 RunOptions。 */
    private record StubDefaults(String logDir) implements RunOptions {
        @Override public String model() { return null; }
        @Override public int maxTokens() { return 16384; }
        @Override public int maxTurns() { return 4000; }
        @Override public int maxDays() { return 365; }
        @Override public double initialBalance() { return 100000.0; }
        @Override public double dailyFee() { return 50.0; }
        @Override public int maxTokenCapacity() { return 128000; }
        @Override public String tokenizerPath() { return null; }
        @Override public String jobFile() { return null; }
        @Override public int runs() { return 1; }
        @Override public Long seed() { return null; }
        @Override public String dataDir() { return null; }
    }
}
```

测试里的 `RecordingLauncher` 覆盖了 `start`，所以 `launch` 必须把前置校验与真正起服务**分成两个方法**；`StubDefaults` 是嵌套 record，Java 允许先使用后声明。

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest='DashboardLauncherTest,DashboardLauncherPathTest'`
Expected: 编译失败 —— `DashboardLauncher` 不存在。

- [ ] **Step 4: 实现 `DashboardLauncher`**

签名以测试为准：`launch(String host, int port, String catalog, RunOptions serverDefaults, Path workingDir, String logDirOverride)`，内部把真正起服务的部分抽成包级可覆盖的 `start(...)`。

```java
package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.app.config.EffectiveEnvironment;
import io.github.ecommercebench.app.config.LlmClientProvider;
import io.github.ecommercebench.app.config.LlmClientProviders;
import io.github.ecommercebench.app.config.ResourcePaths;
import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.job.BenchmarkRunLauncher;
import io.github.ecommercebench.app.job.JobService;
import io.github.ecommercebench.app.job.ModelKeyGuard;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader;
import io.github.ecommercebench.evaluation.io.SupplierRosterReader.RosterEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 起 Web 服务的共用编排，被 {@code serve} 子命令与根命令的无 {@code --model} 分支共同委托。
 *
 * <p>与批处理运行不同，这是唯一会绑定端口的路径（默认仅监听本地回环）。进程阻塞直到收到中断，退出码 0；
 * 日志目录不存在或端口不可用返回 2。
 *
 * <p>密钥与 provider 的构造**全部延后到 {@link #launch} 内部**，因此 {@code new DashboardLauncher()} 本身
 * 不触发任何文件读取或 stderr 输出——这让 {@code BenchmarkCommand} 的无参构造在测试里保持安静。
 *
 * <p>{@code start} 为包级可覆盖接缝，使测试无需真绑端口即可验证前置校验与参数透传。
 */
public class DashboardLauncher {

    /**
     * 校验目录、装配服务、阻塞至中断。
     *
     * @param logDirOverride 会话根目录覆盖值，null 表示 {@code workingDir/log}
     * @return 退出码：正常中断 0，目录不存在或启动失败 2
     */
    public int launch(
            String host,
            int port,
            String catalog,
            RunOptions serverDefaults,
            Path workingDir,
            String logDirOverride) {
        Path root = resolveLogDir(logDirOverride, workingDir);
        if (!Files.isDirectory(root)) {
            System.err.println("日志目录不存在: " + root);
            return 2;
        }
        return start(host, port, catalog, serverDefaults, root, workingDir);
    }

    /** 真正装配并阻塞的部分；包级可覆盖以便测试拦截。 */
    int start(
            String host,
            int port,
            String catalog,
            RunOptions serverDefaults,
            Path root,
            Path workingDir) {
        ObjectMapper mapper = new ObjectMapper();
        EffectiveEnvironment environment = EffectiveEnvironment.load();
        Map<String, RosterEntry> roster =
                new SupplierRosterReader().readByEmail(resolveCatalog(catalog, workingDir));
        ModelKeyGuard keys =
                new ModelKeyGuard(
                        ResourcePaths.of(workingDir)
                                .modelsConfig(environment.asMap().get("ECBENCH_MODELS_CONFIG")),
                        environment.asMap(),
                        mapper);
        LlmClientProvider provider = LlmClientProviders.fromEnvironment(environment.asMap(), mapper);
        JobService jobs =
                new JobService(
                        new BenchmarkRunLauncher(mapper, provider),
                        keys,
                        serverDefaults,
                        environment.asMap(),
                        workingDir,
                        mapper);
        CountDownLatch stopped = new CountDownLatch(1);
        try (DashboardServer server =
                new DashboardServer(root, host, port, roster, new ModelCatalogService(keys, mapper), jobs)) {
            server.start();
            System.out.printf("Dashboard: http://%s:%d/ （log-dir=%s）%n", host, server.port(), root);
            awaitShutdown(stopped);
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IOException exception) {
            System.err.println("启动失败: " + exception.getMessage());
            return 2;
        }
    }

    /** 名册路径解析：{@code --catalog} 覆盖优先，否则回退到资源根 {@code data/suppliers.csv}。 */
    static Path resolveCatalog(String override, Path workingDir) {
        ResourcePaths paths = ResourcePaths.of(workingDir);
        Path custom = paths.optional(override);
        return custom != null ? custom : paths.dataDir(null).resolve("suppliers.csv");
    }

    /** 会话根解析：覆盖值优先（相对路径按工作目录解析），否则 {@code workingDir/log}。 */
    static Path resolveLogDir(String override, Path workingDir) {
        ResourcePaths paths = ResourcePaths.of(workingDir);
        Path custom = paths.optional(override);
        return (custom != null ? custom : workingDir.resolve("log")).normalize();
    }

    /** 注册关闭钩子并阻塞，直到进程被中断。 */
    private void awaitShutdown(CountDownLatch stopped) throws InterruptedException {
        Thread hook = new Thread(stopped::countDown);
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM 正在关闭：无需再注册钩子，直接等待。
        }
        try {
            stopped.await();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // 关闭流程中移除钩子会抛此异常，忽略即可。
            }
        }
    }

    private static Path currentWorkingDir() {
        return Path.of(System.getProperty("user.dir"));
    }
}
```

`currentWorkingDir()` 仅供 `ServeCommand` / `BenchmarkCommand` 调用方使用；`start` 自己不推导工作目录，而是由 `launch` 显式传入，避免两处各算一次而分叉。若最终无人调用它，则删掉该方法（Checkstyle 不拦未用私有方法，靠这一步自查）。

**可见性约束**：`start` 是包级方法，只有 `app.web` 包内的测试（`DashboardLauncherTest`）能覆盖它。`app.cli` 包里的 `BenchmarkCommandTest` **无法覆盖 `start`**（跳包子类看不到包级成员，写 `@Override` 会直接编译失败），因此它只能覆盖 public 的 `launch`。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest='DashboardLauncherTest,DashboardLauncherPathTest'`
Expected: PASS，3 + 3 tests。

- [ ] **Step 6: 瘦身 `ServeCommand`**

整体替换为：

```java
package io.github.ecommercebench.app.cli;

import io.github.ecommercebench.app.config.RunOptions;
import io.github.ecommercebench.app.web.DashboardLauncher;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 子命令 {@code serve}：启动本地 Web dashboard，浏览、对比 {@code log} 目录下的 run 产物，并可提交新的运行。
 *
 * <p>与根命令无 {@code --model} 时的行为完全一致，二者都委托 {@link DashboardLauncher}；保留本子命令只为
 * 显式表达意图与向后兼容既有脚本。
 */
@Command(name = "serve", description = "启动本地 Web dashboard 浏览 / 对比 / 提交 run")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--log-dir", description = "会话根目录（每个子目录=一个会话）", defaultValue = "log")
    private Path logDir;

    @Mixin private ServeOptions serve = new ServeOptions();

    private final DashboardLauncher launcher;

    /** 供 Picocli 反射实例化的无参构造。 */
    public ServeCommand() {
        this(new DashboardLauncher());
    }

    /** 注入启动器，便于测试拦截。 */
    public ServeCommand(DashboardLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public Integer call() {
        return launcher.launch(
                serve.host(), serve.port(), serve.catalog(), serverDefaults(), workingDir(), logDir.toString());
    }

    /**
     * 把 serve 自己的 {@code --log-dir} 转成一份 {@link RunOptions}，作为 Web 提交任务的 4 个路径类字段来源。
     *
     * <p>复用 Picocli 解析而不是另写一个 13 方法的实现，可保证缺省值与 CLI 永远同源。
     */
    private static RunOptions serverDefaults() {
        return new CliRunOptions();
    }

    private static Path workingDir() {
        return Path.of(System.getProperty("user.dir"));
    }
}
```

**上面 `serverDefaults()` 返回空 `CliRunOptions` 是不对的**——那样 `logDir()` 为 null，`JobService.logDir()` 会回退到 `workingDir/log`，与 `serve --log-dir ./custom` 不一致。改为真的把 `--log-dir` 解析进去：

```java
    private RunOptions serverDefaults() {
        CliRunOptions defaults = new CliRunOptions();
        new CommandLine(defaults).parseArgs("--log-dir", logDir.toString());
        return defaults;
    }
```

（去掉 `static`。）

- [ ] **Step 7: 跑测试确认通过并检查 `serve` 仍可用**

Run: `mvn -o -pl ecbench-app test`
Expected: PASS，无失败。

Run: `mvn -o -DskipTests -pl ecbench-app -am package` 然后
`java -jar ecbench-app/target/ecbench-app.jar serve --port 0 --log-dir log &`
用 `curl -sS -D - -o /tmp/models.json http://127.0.0.1:<port>/api/models` 单条核对 200 与 `Content-length`，再 `kill <PID>`。
Expected: 200，body 含 18 个模型键与 `npc` 段。

- [ ] **Step 8: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeOptions.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java \
        ecbench-app/src/main/java/io/github/ecommercebench/app/web/DashboardLauncher.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardLauncherTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/web/DashboardLauncherPathTest.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/cli/ServeCommandCatalogTest.java
git commit -m "refactor(app): share dashboard startup between serve and the root command"
```

---

### Task 15: 根命令默认起 Web 服务

**Files:**
- Modify: `ecbench-app/src/main/java/io/github/ecommercebench/app/cli/BenchmarkCommand.java`
- Modify: `ecbench-app/src/test/java/io/github/ecommercebench/app/cli/BenchmarkCommandTest.java`

**Interfaces:**
- Consumes: `DashboardLauncher.launch(host, port, catalog, serverDefaults, workingDir, logDirOverride)`（Task 14）；`ServeOptions`（Task 14）；`CliRunOptions`。
- Produces: 构造重载 `BenchmarkCommand(RunComponentFactory factory, DashboardLauncher launcher)`。**`EcommerceBenchApplication` 无需改动**——它调的是 2 参构造 `BenchmarkCommand(factory)`，该构造委托为 `(factory, new DashboardLauncher())`。

**必须处理的既有测试冲突**：`BenchmarkCommandTest.modelIsRequired` 断言 `exitCode("--max-days","1")` 非零。新语义下这条命令会**启动服务并永久阻塞**，测试直接挂死。本任务必须重写它。

- [ ] **Step 1: 重写测试（先让它失败）**

把 `BenchmarkCommandTest` 的 `modelIsRequired` 整个替换为下面四个测试，并在类内加一个记录型 launcher：

```java
    /**
     * 记录参数并立即返回 0，绝不真绑端口或阻塞。
     *
     * <p>只能覆盖 public 的 {@code launch}：{@code DashboardLauncher.start} 是包级方法，而本测试在
     * {@code app.cli} 包，跳包子类无法覆盖它（写 {@code @Override} 会编译失败）。
     */
    private static final class RecordingLauncher extends io.github.ecommercebench.app.web.DashboardLauncher {
        private int calls;
        private String host;
        private int port;
        private io.github.ecommercebench.app.config.RunOptions defaults;
        private String logDirOverride;

        @Override
        public int launch(String host, int port, String catalog,
                io.github.ecommercebench.app.config.RunOptions serverDefaults,
                java.nio.file.Path workingDir, String logDirOverride) {
            this.calls++;
            this.host = host;
            this.port = port;
            this.defaults = serverDefaults;
            this.logDirOverride = logDirOverride;
            return 0;
        }
    }

    @Test
    void startsDashboardWhenModelIsAbsent() {
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = new CommandLine(new BenchmarkCommand(null, launcher)).execute("--port", "0");

        assertThat(exit).isZero();
        assertThat(launcher.calls).isEqualTo(1);
        assertThat(launcher.host).isEqualTo("127.0.0.1");
        assertThat(launcher.port).isZero();
        assertThat(launcher.logDirOverride).isNull();
    }

    @Test
    void blankModelAlsoStartsDashboard() {
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = new CommandLine(new BenchmarkCommand(null, launcher)).execute("--model", "   ");

        assertThat(exit).isZero();
        assertThat(launcher.calls).isEqualTo(1);
    }

    @Test
    void serveOptionsReachTheLauncher() {
        RecordingLauncher launcher = new RecordingLauncher();

        new CommandLine(new BenchmarkCommand(null, launcher))
                .execute("--host", "0.0.0.0", "--port", "9999", "--log-dir", "custom-log");

        assertThat(launcher.host).isEqualTo("0.0.0.0");
        assertThat(launcher.port).isEqualTo(9999);
        assertThat(launcher.logDirOverride).isEqualTo("custom-log");
        assertThat(launcher.defaults.logDir()).isEqualTo("custom-log");
    }

    @Test
    void batchPathIsUnchangedAndStillNeedsAWiredFactory() {
        RecordingLauncher launcher = new RecordingLauncher();

        int exit = new CommandLine(new BenchmarkCommand(null, launcher)).execute("--model", "gpt");

        assertThat(exit).isEqualTo(2);
        assertThat(launcher.calls).isZero();
    }
```

`batchPathIsUnchangedAndStillNeedsAWiredFactory` 依赖仓库根存在 `models_config.json`（Surefire 的工作目录即模块目录 `ecbench-app`，`ResourcePaths.repoRoot()` 会向上探测到仓库根，故成立——`ConfigurationMergerTest` 已用同样机制）。若该测试因配置解析返回 2 而非工厂未装配返回 2，断言仍然是 2，不影响判定；但要额外确认 `launcher.calls` 为 0，这一条才是「批处理路径没被误改成 serve」的真证据。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl ecbench-app test -Dtest=BenchmarkCommandTest`
Expected: 编译失败 —— `BenchmarkCommand(RunComponentFactory, DashboardLauncher)` 不存在。

- [ ] **Step 3: 改 `BenchmarkCommand`**

类声明与字段/构造区替换为：

```java
@Command(
        name = "ecbench",
        mixinStandardHelpOptions = true,
        version = "ecbench 1.0",
        description = "E-Commerce Bench 运行器（无 --model 时启动 Web dashboard）",
        subcommands = {EvaluationCommand.class, ServeCommand.class})
public final class BenchmarkCommand implements Callable<Integer> {

    @Mixin
    private CliRunOptions options = new CliRunOptions();

    @Mixin
    private ServeOptions serve = new ServeOptions();

    private final RunComponentFactory factory;
    private final DashboardLauncher launcher;

    /** 供 Picocli 反射实例化的无参构造；运行组件工厂稍后由装配层注入，在此之前为 null。 */
    public BenchmarkCommand() {
        this(null);
    }

    /** 注入运行组件工厂，call 时交给 RunCoordinator 执行各 run。 */
    public BenchmarkCommand(RunComponentFactory factory) {
        this(factory, new DashboardLauncher());
    }

    /** 注入运行组件工厂与 dashboard 启动器；后者可被测试替换为记录型实现。 */
    public BenchmarkCommand(RunComponentFactory factory, DashboardLauncher launcher) {
        this.factory = factory;
        this.launcher = launcher;
    }
```

把 `call()` 开头的 model 校验分支替换为：

```java
    @Override
    public Integer call() {
        if (options.model() == null || options.model().isBlank()) {
            return launcher.launch(
                    serve.host(),
                    serve.port(),
                    serve.catalog(),
                    options,
                    Path.of(System.getProperty("user.dir")),
                    options.logDir());
        }
        BenchmarkOptions merged;
```

（`call()` 余下部分**一字不改**。）同时更新类级 Javadoc：把「校验 --model」的描述改为「无 --model 时启动 dashboard，有则合并配置并交由 RunCoordinator 并行执行」，并保留退出码说明（新增：serve 分支正常中断 0、目录不存在或端口不可用 2）。

import 追加 `io.github.ecommercebench.app.web.DashboardLauncher`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl ecbench-app test -Dtest=BenchmarkCommandTest`
Expected: PASS，6 tests（原 2 个参数测试 + 新 4 个）。

- [ ] **Step 5: 手工验证默认起服务**

Run: `mvn -o -DskipTests -pl ecbench-app -am package`
Run: `java -jar ecbench-app/target/ecbench-app.jar --port 0 > /tmp/ecbench-serve.log 2>&1 &`
记下 PID，从 `/tmp/ecbench-serve.log` 读出实际端口，然后：

```bash
curl -sS -D - -o /tmp/sessions.json http://127.0.0.1:<PORT>/api/sessions
curl -sS -D - -o /tmp/models.json   http://127.0.0.1:<PORT>/api/models
curl -sS -D - -o /tmp/jobs.json     http://127.0.0.1:<PORT>/api/jobs
```

Expected: 三条都是 200；`/tmp/jobs.json` 为 `{"jobs":[]}`。逐条核对 `Content-length` 与 body 字节数一致。
再 `java -jar ecbench-app/target/ecbench-app.jar --model gpt` → 应立即退出码 2（工厂未装配走不到，实际会因缺 API key 或工厂已装配而跑；此处只确认**没有起服务**）。
最后 `kill <PID>`。

- [ ] **Step 6: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/java/io/github/ecommercebench/app/cli/BenchmarkCommand.java \
        ecbench-app/src/test/java/io/github/ecommercebench/app/cli/BenchmarkCommandTest.java
git commit -m "feat(app)!: start the web dashboard when no model is given"
```

提交信息带 `!` 标记行为变更：`java -jar ecbench-app.jar`（无参数）从「打印缺少 --model 并退出 2」变为「启动服务并阻塞」。

---

### Task 16: 前端 —— `boot()` 重构、`apiSend` 与「新建运行」面板

**Files:**
- Modify: `ecbench-app/src/main/resources/web/index.html`（aside 内新增一个 `.rail-block`）
- Modify: `ecbench-app/src/main/resources/web/app.js`（`boot()` 重构 + `apiSend` + 表单逻辑）
- Modify: `ecbench-app/src/main/resources/web/app.css`（表单样式）

**Interfaces:**
- Consumes: `GET /api/models`（Task 11）、`POST /api/jobs`（Task 12）。
- Produces: `apiSend(method, path, body)`、`initRunForm()`、`showRunError(text)`。Task 17 依赖 `apiSend` 与重构后的 `boot()`。

**为什么必须重构 `boot()`**：现有实现在 `state.sessions.length === 0` 时**直接 `return`**，后面的事件绑定与渲染全部跳过。新语义下用户很可能先开服务、再在页面上跑第一个 run，此时 `log/` 为空——不重构的话「新建运行」面板永远不可见。

- [ ] **Step 1: `index.html` 加表单**

在 `<aside class="rail">` 内、`<div class="rail-block"><h2 class="micro">会话</h2>` **之前**插入：

```html
  <div class="rail-block">
    <h2 class="micro">新建运行</h2>
    <p id="npc-warning" class="warn" hidden></p>
    <form id="run-form" class="run-form">
      <label class="field"><span>模型</span>
        <select id="run-model" required><option value="">读取模型列表中…</option></select>
      </label>
      <div class="field-row">
        <label class="field"><span>天数</span><input id="run-days" type="number" min="1" step="1" placeholder="365" /></label>
        <label class="field"><span>轮数</span><input id="run-turns" type="number" min="1" step="1" placeholder="4000" /></label>
      </div>
      <div class="field-row">
        <label class="field"><span>并行 run</span><input id="run-runs" type="number" min="1" step="1" placeholder="1" /></label>
        <label class="field"><span>种子</span><input id="run-seed" type="number" step="1" placeholder="42" /></label>
      </div>
      <button id="run-submit" type="submit">提交运行</button>
      <p id="run-error" class="error" hidden></p>
      <p class="note">密钥取自服务进程的环境变量与仓库根 <code>.env</code>，不经浏览器传输。</p>
    </form>
  </div>
```

- [ ] **Step 2: `app.js` 加 `apiSend`**

在既有 `async function api(path) { … }` 之后插入：

```js
/* 写侧请求助手：与只读的 api() 分开，因为提交失败时必须拿到服务端的 error 文案，
   而 api() 只抛 `${status} ${statusText}`，会把具体原因丢掉。 */
async function apiSend(method, path, body) {
  const init = { method, headers: {} };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path, init);
  let payload = null;
  try { payload = await res.json(); } catch (ignored) { payload = null; }
  if (!res.ok) {
    const detail = payload && payload.error ? payload.error : `${res.status} ${res.statusText}`;
    throw new Error(detail);
  }
  return payload;
}
```

- [ ] **Step 3: `app.js` 加表单逻辑**

在 `/* ---------------------------------------------------------------- boot */` 分隔注释之前插入：

```js
/* ------------------------------------------------------------ run form */

function showRunError(text) {
  const box = $('run-error');
  if (!text) { box.hidden = true; box.textContent = ''; return; }
  box.hidden = false;
  box.textContent = text;
}

function fillModelSelect(catalog) {
  const select = $('run-model');
  select.innerHTML = '';
  const ready = catalog.models.filter((m) => m.keyReady);
  if (!ready.length) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = '无可用模型（密钥缺失）';
    select.append(option);
    select.disabled = true;
    showRunError('未检测到任何可用的 LLM 密钥。请在启动服务前 export 对应变量，或在仓库根放置 .env。');
    return;
  }
  catalog.models.forEach((model) => {
    const option = document.createElement('option');
    option.value = model.key;
    option.textContent = model.keyReady
      ? model.key
      : `${model.key}（缺 ${model.missingEnv}）`;
    option.disabled = !model.keyReady;
    select.append(option);
  });
  select.value = ready[0].key;
  select.disabled = false;

  // NPC 模型独立告警：即便主模型就绪，缺 OPENAI_API_KEY 一样跑不起来。
  const warning = $('npc-warning');
  if (catalog.npc && !catalog.npc.keyReady) {
    warning.hidden = false;
    warning.textContent = `供应商模型 ${catalog.npc.modelName} 缺 ${catalog.npc.missingEnv}，任何运行都会失败。`;
  } else {
    warning.hidden = true;
    warning.textContent = '';
  }
}

/* 收集表单：留空的数字字段不发送，让服务端套用与 CLI 同源的缺省值。 */
function collectRunRequest() {
  const payload = { model: $('run-model').value };
  const numeric = [['run-days', 'maxDays'], ['run-turns', 'maxTurns'], ['run-runs', 'runs'], ['run-seed', 'seed']];
  numeric.forEach(([id, field]) => {
    const raw = $(id).value.trim();
    if (raw !== '') payload[field] = Number(raw);
  });
  return payload;
}

async function initRunForm() {
  try {
    fillModelSelect(await api('/api/models'));
  } catch (error) {
    showRunError('无法读取模型列表：' + error.message);
    $('run-model').disabled = true;
    return;
  }
  $('run-form').onsubmit = async (event) => {
    event.preventDefault();
    showRunError('');
    const button = $('run-submit');
    button.disabled = true;
    button.textContent = '提交中…';
    try {
      await apiSend('POST', '/api/jobs', collectRunRequest());
      await refreshJobs();
    } catch (error) {
      showRunError(error.message);
    } finally {
      button.disabled = false;
      button.textContent = '提交运行';
    }
  };
}
```

（`refreshJobs` 由 Task 17 提供。本任务先加一个占位实现，Task 17 会整体替换：`async function refreshJobs() {}`。）

- [ ] **Step 4: 重构 `boot()`**

把整个 `boot()` 替换为：

```js
async function boot() {
  // 写侧面板与事件绑定必须先于「空会话早退」，否则 log/ 为空时用户看不到提交入口。
  await initRunForm();
  $('select-all').onclick = () => {
    state.sessions.forEach((s) => state.selected.add(s.name));
    renderSessions();
    loadCompare();
  };
  $('clear').onclick = () => {
    state.selected.clear();
    renderSessions();
    loadCompare();
  };
  $('metric-filter').oninput = () => filterMetricRows();
  observeReveals();
  refreshJobs();

  try {
    state.sessions = await api('/api/sessions');
  } catch (error) {
    setStatus('无法读取会话列表：' + error.message, 'bad');
    $('session-list').innerHTML = '<p class="hint">加载失败</p>';
    return;
  }
  if (!state.sessions.length) {
    setStatus('日志目录下没有会话');
    $('session-list').innerHTML =
      '<p class="hint">未发现会话。在上方「新建运行」提交第一个任务，或用 --log-dir 指向含 run 产物的目录。</p>';
    return;
  }
  // 默认选中信息最全的会话（run 最多，其次有余额产物）
  const best = [...state.sessions].sort((a, b) => (b.runs - a.runs) || (b.hasBalance - a.hasBalance))[0];
  state.selected.add(best.name);
  renderSessions();
  await loadCompare();
}
```

`observeReveals()` 从原位置（`renderSessions()` 之后）移到了早退之前——它对 `.reveal` 元素做 IntersectionObserver 注册，新增的卡片也带 `reveal` 类，必须在 DOM 就绪后尽早调用一次。

- [ ] **Step 5: `app.css` 加样式**

追加（2 空格缩进，沿用浅色主题的既有变量；若变量名与文件内实际不符，以 `app.css` 里既有的颜色变量为准替换）：

```css
.run-form { display: flex; flex-direction: column; gap: 8px; }
.run-form .field { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.run-form .field > span { font-size: 11px; opacity: 0.72; }
.run-form .field-row { display: flex; gap: 8px; }
.run-form .field-row .field { flex: 1 1 0; }
.run-form select,
.run-form input {
  width: 100%;
  min-width: 0;
  padding: 5px 7px;
  font: inherit;
  font-size: 12px;
  border: 1px solid var(--line, #d8d2c4);
  border-radius: 6px;
  background: var(--card, #fffdf7);
  color: inherit;
}
.run-form button[type="submit"] {
  padding: 7px 10px;
  font: inherit;
  font-size: 12px;
  border: 1px solid var(--line, #d8d2c4);
  border-radius: 6px;
  background: var(--accent, #b0761a);
  color: #fff;
  cursor: pointer;
}
.run-form button[type="submit"]:disabled { opacity: 0.55; cursor: progress; }
.run-form .error { margin: 0; font-size: 11px; color: #bd4136; white-space: normal; }
.run-form .note { margin: 0; font-size: 10.5px; opacity: 0.66; white-space: normal; }
.warn {
  margin: 0 0 8px;
  padding: 6px 8px;
  font-size: 11px;
  border-left: 3px solid #bd4136;
  background: rgba(189, 65, 54, 0.08);
  border-radius: 4px;
  white-space: normal;
}
```

**必须显式写 `min-width: 0` 与 `white-space: normal`**：既有 `.dimension` 就是因为缺 `min-width:0` 叠加全局 `white-space:nowrap` 而必然溢出（阶段 B 终审缺陷 ③）。

- [ ] **Step 6: 浏览器人工验证**

启动服务（`java -jar ecbench-app/target/ecbench-app.jar --port 8081 --log-dir <空临时目录>`），打开 `http://127.0.0.1:8081/`：
1. 空 `log/` 下「新建运行」表单**可见**，模型下拉已填充，缺密钥的项灰掉并标注变量名。
2. 浏览器控制台 **0 报错**。
3. 若 `OPENAI_API_KEY` 未设，NPC 告警条可见。
4. 不填任何数字直接提交（在有密钥的环境下）→ 任务队列出现一条 QUEUED/RUNNING；在无密钥环境下 → 内联错误区显示服务端的具体文案。
5. 侧栏与主区无横向溢出（桌面宽度 1440 与窄屏 641 各看一次）。

无 LLM 密钥时第 4 步只验错误文案（400 拦截路径）。

- [ ] **Step 7: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped（前端无单测，此步只防 Java 侧回归）。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/resources/web/index.html \
        ecbench-app/src/main/resources/web/app.js \
        ecbench-app/src/main/resources/web/app.css
git commit -m "feat(web): submit runs from the dashboard, keep the form visible on an empty log"
```

---

### Task 17: 前端 —— 「任务队列」面板与轮询

**Files:**
- Modify: `ecbench-app/src/main/resources/web/index.html`（main 内新增一个 section）
- Modify: `ecbench-app/src/main/resources/web/app.js`（替换 Task 16 的 `refreshJobs` 占位）
- Modify: `ecbench-app/src/main/resources/web/app.css`（状态徽章与进度条）

**Interfaces:**
- Consumes: `GET /api/jobs` 的 `{"jobs":[JobView…]}`、`DELETE /api/jobs/{id}` 的 `{"id":…,"state":…}`（Task 13）；`apiSend`（Task 16）。
- Produces: `refreshJobs()`、`renderJobs()`、`scheduleJobPolling()`；`state.jobs`。

- [ ] **Step 1: `index.html` 加面板**

在 `<main class="stage">` 之后、`<section class="kpis" id="kpis">` **之前**插入：

```html
  <section class="grid" id="jobs-section">
    <article class="card span-12 reveal">
      <header class="card-head">
        <h2>任务队列</h2>
        <span class="micro" id="jobs-note">单任务串行 · 状态仅存内存，服务重启后清空</span>
      </header>
      <div id="jobs" class="table-wrap"><p class="hint">暂无任务</p></div>
    </article>
  </section>
```

- [ ] **Step 2: `app.js` 替换 `refreshJobs` 占位并加渲染**

删掉 Task 16 留下的 `async function refreshJobs() {}`，在 `/* ------------------------------------------------------------ run form */` 段之后插入：

```js
/* ------------------------------------------------------------- jobs */

const STATE_ZH = {
  QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
};

let jobGeneration = 0;
let jobTimer = null;
let finishedSeen = new Set();

async function refreshJobs() {
  const generation = ++jobGeneration;
  let payload;
  try {
    payload = await api('/api/jobs');
  } catch (error) {
    $('jobs').innerHTML = `<p class="hint">读取任务失败：${esc(error.message)}</p>`;
    return;
  }
  if (generation !== jobGeneration) return;   // 丢弃陈旧响应，与 loadCompare 同构
  state.jobs = payload.jobs || [];
  renderJobs();
  notifyNewlyFinished();
  scheduleJobPolling();
}

function notifyNewlyFinished() {
  const finished = state.jobs.filter((job) => job.state === 'SUCCEEDED');
  const fresh = finished.filter((job) => !finishedSeen.has(job.id));
  fresh.forEach((job) => finishedSeen.add(job.id));
  // 有新完成的会话时重扫会话列表，让新产物立刻可选。
  if (fresh.length) reloadSessions();
}

async function reloadSessions() {
  try {
    state.sessions = await api('/api/sessions');
  } catch (ignored) {
    return;
  }
  renderSessions();
}

function scheduleJobPolling() {
  const active = state.jobs.some((job) => job.state === 'QUEUED' || job.state === 'RUNNING');
  if (active && jobTimer === null) {
    jobTimer = setTimeout(() => { jobTimer = null; refreshJobs(); }, 2500);
  }
  if (!active && jobTimer !== null) {
    clearTimeout(jobTimer);
    jobTimer = null;
  }
}

function renderJobs() {
  const box = $('jobs');
  box.innerHTML = '';
  if (!state.jobs.length) {
    box.innerHTML = '<p class="hint">暂无任务。用左侧「新建运行」提交第一个。</p>';
    return;
  }
  const table = document.createElement('table');
  table.innerHTML =
    '<thead><tr><th>模型</th><th>状态</th><th>进度</th><th>会话</th><th>提交时间</th>'
    + '<th></th><th>详情</th></tr></thead>';
  const tbody = document.createElement('tbody');
  state.jobs.forEach((job) => tbody.append(jobRow(job)));
  table.append(tbody);
  box.append(table);
}

function jobRow(job) {
  const row = document.createElement('tr');
  row.append(cell(job.model));
  row.append(badgeCell(job.state));
  row.append(progressCell(job.progress));
  row.append(sessionCell(job));
  row.append(cell(String(job.submittedAt || '').replace('T', ' ').slice(0, 19)));

  const action = document.createElement('td');
  if (job.state === 'QUEUED' || job.state === 'RUNNING') {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = '取消';
    button.title = '尽力而为：可能需等到下一次模型调用才真正停下';
    button.onclick = async () => {
      button.disabled = true;
      try {
        await apiSend('DELETE', `/api/jobs/${encodeURIComponent(job.id)}`);
      } catch (error) {
        showRunError(error.message);
      }
      await refreshJobs();
    };
    action.append(button);
  }
  row.append(action);
  row.append(cell(job.state === 'FAILED' ? (job.error || '') : outcomeText(job)));
  return row;
}

function outcomeText(job) {
  if (!job.outcomes || !job.outcomes.length) return '';
  return job.outcomes
    .map((o) => `run${o.index}: ${o.succeeded ? `${o.canonicalReason}（day ${o.finalDay}）` : '失败'}`)
    .join(' · ');
}

function cell(text) {
  const td = document.createElement('td');
  td.textContent = text ?? '';
  return td;
}

function badgeCell(state) {
  const td = document.createElement('td');
  const span = document.createElement('span');
  span.className = `badge badge-${state.toLowerCase()}`;
  span.textContent = STATE_ZH[state] || state;
  td.append(span);
  return td;
}

function progressCell(progress) {
  const td = document.createElement('td');
  if (!progress || !progress.maxDays) { td.textContent = '—'; return td; }
  const ratio = Math.max(0, Math.min(1, progress.days / progress.maxDays));
  const bar = document.createElement('div');
  bar.className = 'bar';
  const fill = document.createElement('i');
  fill.style.width = `${(ratio * 100).toFixed(1)}%`;
  bar.append(fill);
  const label = document.createElement('span');
  label.className = 'bar-label';
  label.textContent = `${progress.days}/${progress.maxDays} 天 · run ${progress.runsDone}/${progress.runsTotal}`;
  td.append(bar, label);
  return td;
}

/* 会话名可点击：跳到会话列表并选中，衔接既有下钻面板。 */
function sessionCell(job) {
  const td = document.createElement('td');
  if (!job.sessionName) { td.textContent = '—'; return td; }
  const link = document.createElement('button');
  link.type = 'button';
  link.className = 'link';
  link.textContent = shortName(job.sessionName);
  link.title = job.sessionName;
  link.onclick = () => {
    state.selected.clear();
    state.selected.add(job.sessionName);
    renderSessions();
    loadCompare();
    $('session-list').scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  td.append(link);
  return td;
}
```

在 `state` 对象上补一个字段：

```js
const state = { sessions: [], selected: new Set(), drilled: null, balance: new Map(), jobs: [] };
```

- [ ] **Step 3: `app.css` 加徽章与进度条**

```css
.badge {
  display: inline-block;
  padding: 2px 7px;
  font-size: 11px;
  border-radius: 999px;
  border: 1px solid var(--line, #d8d2c4);
  white-space: nowrap;
}
.badge-queued { background: rgba(154, 127, 18, 0.12); }
.badge-running { background: rgba(38, 128, 143, 0.14); }
.badge-succeeded { background: rgba(61, 139, 80, 0.14); }
.badge-failed { background: rgba(189, 65, 54, 0.14); }
.badge-cancelled { background: rgba(120, 120, 120, 0.14); }

.bar {
  position: relative;
  height: 6px;
  min-width: 90px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.08);
  overflow: hidden;
}
.bar > i { display: block; height: 100%; background: var(--accent, #26808f); }
.bar-label { display: block; margin-top: 3px; font-size: 10.5px; opacity: 0.72; white-space: nowrap; }

#jobs button.link {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  font-size: 12px;
  color: var(--accent, #26808f);
  cursor: pointer;
  text-decoration: underline;
}
#jobs td { white-space: normal; min-width: 0; }
```

`#jobs td { white-space: normal; }` 是必需的：全局有 `white-space: nowrap`，不覆盖会让「详情」列的错误文案把表格撑破。

- [ ] **Step 4: 浏览器人工验证**

1. 打开看板，任务队列表格渲染出表头与空态文案。
2. 提交一个任务 → 2.5 秒内出现 QUEUED/RUNNING，进度条随 `balance.csv` 行数增长（短任务可能一直是 0/365，属正常）。
3. 点「取消」→ 状态变「已取消」；按钮消失。
4. 全部任务终态后，**打开 DevTools 网络面板确认轮询停止**（不再有周期性 `/api/jobs` 请求）。
5. 任务成功后会话列表自动出现新会话；点会话名 → 对比区与下钻面板加载该会话。
6. 控制台 0 报错；快速连续提交两个任务再刷新页面，无「旧响应覆盖新状态」的错乱。
7. 桌面宽度 1440 与窄屏 641 各检查一次表格无横向溢出。

无 LLM 密钥时，第 2、3、5 步无法验证真实运行；改为提交后立刻观察 400 拦截文案，并记录「真数据验收待用户提供 key」。

- [ ] **Step 5: 全量门禁 + 提交**

Run: `mvn -o clean test` → BUILD SUCCESS，0 failures，1 skipped。
Run: `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check` → 0 违规。

```bash
git add ecbench-app/src/main/resources/web/index.html \
        ecbench-app/src/main/resources/web/app.js \
        ecbench-app/src/main/resources/web/app.css
git commit -m "feat(web): poll and render the job queue with cancel and progress"
```

---

### Task 18: 文档与最终验收

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-10-14-web-triggered-runs-design.md`（状态改为「已实施」）

**Interfaces:**
- Consumes: 前 17 个任务的全部交付物。
- Produces: 无代码接口；本任务的交付物是文档与一份可复核的验收记录。

- [ ] **Step 1: 改 README 的启动小节**

把「应用以 Spring Boot `WebApplicationType.NONE` 启动，不监听任何端口。」这句所在的段落改为：

```markdown
应用以 Spring Boot `WebApplicationType.NONE` 启动，Spring 自身不监听任何端口。

**不带 `--model` 时，进程会启动本地 Web dashboard 并阻塞**（端口由内置的 JDK HttpServer 绑定，默认
`127.0.0.1:8080`）；带 `--model` 时行为与从前完全一致，跑批处理并按 0/1/2 退出。

```bash
# 起 Web 看板（默认行为）
java -jar ecbench-app/target/ecbench-app.jar

# 等价于显式的 serve 子命令
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080

# 跑批处理（与从前逐字一致）
java -jar ecbench-app/target/ecbench-app.jar --model gemini-3.5-flash --max-days 10 --max-turns 50
```
```

- [ ] **Step 2: README 新增「Web 触发运行」小节**

放在「本地 Web 看板（serve）」小节之后：

```markdown
### 在网页上提交运行

看板除了浏览与对比，还能直接提交新的运行。

**密钥**：服务进程启动时读取「环境变量 + 仓库根 `.env`」，**真实环境变量优先，`.env` 只填补缺失项**。
密钥永不经浏览器传输。`.env` 已加入 `.gitignore`，格式为每行 `KEY=VALUE`：

```
DASHSCOPE_API_KEY=sk-xxxx
OPENAI_API_KEY=sk-yyyy
```

> **坑**：供应商 NPC 模型（`npc_tools`，默认 `gpt-4o-mini`）走 openai，**每个 run 都需要 `OPENAI_API_KEY`**，
> 即便主模型选的是 qwen / glm / kimi。缺它时任何运行都会在装配阶段失败；下拉列表上方的告警条会提前指出。

**接口**：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/models` | 全部模型键 + 各自的密钥就绪状态 + 独立的 `npc` 段 |
| POST | `/api/jobs` | 提交运行，返回 `202` 与任务 id |
| GET | `/api/jobs` | 任务列表（含进度） |
| GET | `/api/jobs/{id}` | 单个任务详情 |
| DELETE | `/api/jobs/{id}` | 取消任务 |

可提交字段：`model`（必填）、`maxTokens`、`maxTurns`、`maxDays`、`initialBalance`、`dailyFee`、
`maxTokenCapacity`、`runs`、`seed`；缺省值与 CLI 完全一致。
**`logDir` / `dataDir` / `tokenizerPath` / `jobFile` 不接受 HTTP 输入**，一律取服务进程的启动配置。

```bash
curl -sS -X POST http://127.0.0.1:8080/api/jobs \
     -H 'Content-Type: application/json' \
     -d '{"model":"qwen3.7-max","maxDays":10,"maxTurns":50}'
```

**调度**：全局单任务串行队列（上限 8 个未结束任务，超出返回 `409`）；一个任务内部仍可用 `runs` 并行多个 run。
进度从已落盘产物推导——`days` 取 `balance/run_<i>_daily_balance.csv` 的数据行数最大值，`runsDone` 取
`metrics/run_<i>_analysis.json` 的个数。

**取消是尽力而为**：`DELETE` 会中断执行线程，级联打断正在阻塞的模型调用；但 agent 主循环不检查中断标志，
若中断落在两次调用之间的纯计算段，要等下一个阻塞点才生效。已产出的部分产物留在磁盘。

**状态只存内存**：服务重启后任务列表清空；历史 run 的产物仍在 `log/` 下，会话扫描照常可见。

**跨站防护**：`POST` / `DELETE` 校验 `Origin`，只放行缺失（curl）或等于 `http://127.0.0.1:<port>` /
`http://localhost:<port>` 的请求，其余返回 `403`。
```

- [ ] **Step 3: README 更新 serve 选项表**

在 serve 选项那段补一句：`--port` / `--host` / `--catalog` 现在也被根命令接受（`java -jar … --port 8081`），因为两条路径共用同一套启动编排。

- [ ] **Step 4: 把 spec 状态改为已实施**

`docs/superpowers/specs/2026-10-14-web-triggered-runs-design.md` 第 4 行 `- 状态：待评审` 改为 `- 状态：已实施（见 docs/superpowers/plans/2026-10-14-web-triggered-runs.md）`。

- [ ] **Step 5: 跑全量门禁并记录实测数字**

```bash
mvn -o clean test
mvn -pl ecbench-evaluation,ecbench-app checkstyle:check
grep -c 'new MetricDef' ecbench-evaluation/src/main/java/io/github/ecommercebench/evaluation/RunComparator.java
```

Expected: BUILD SUCCESS / 0 failures / 1 skipped；checkstyle 0 违规；`METRIC_DEFS` 行数仍为 29（若 `grep -c` 的模式与实际写法不符，改用能数出 29 的等价模式，**目标是确认这个冻结契约没被动过**）。

把实测的 tests 总数写进本计划的执行记录。

- [ ] **Step 6: 端到端冒烟**

```bash
mvn -o -DskipTests -pl ecbench-app -am package
java -jar ecbench-app/target/ecbench-app.jar --port 8081 > /tmp/ecbench.log 2>&1 &
PID=$!
sleep 3
curl -sS -D - -o /tmp/b1.json http://127.0.0.1:8081/api/models
curl -sS -D - -o /tmp/b2.json http://127.0.0.1:8081/api/jobs
curl -sS -D - -o /tmp/b3.json -X POST http://127.0.0.1:8081/api/jobs \
     -H 'Content-Type: application/json' -d '{"model":"nope"}'
curl -sS -D - -o /tmp/b4.json -X POST http://127.0.0.1:8081/api/jobs \
     -H 'Content-Type: application/json' -H 'Origin: http://evil.example' -d '{"model":"gpt-5.5"}'
curl -sS -D - -o /tmp/b5.json http://127.0.0.1:8081/api/nope
curl -sS -D - -o /tmp/b6.json -X DELETE http://127.0.0.1:8081/api/sessions
kill $PID
```

**逐条单独核对**响应行的状态码（不要拼成一行多命令，会串码）：
`200` / `200` / `400`（未知模型键）/ `403`（外来 Origin）/ `404`（未知端点）/ `405`（只读端点上 DELETE）。
每条再核对 `Content-length` 与 body 字节数一致。

若用户提供了 LLM API key，追加一次真跑：
```bash
curl -sS -X POST http://127.0.0.1:8081/api/jobs -H 'Content-Type: application/json' \
     -d '{"model":"<有密钥的模型键>","maxDays":2,"maxTurns":20}'
```
然后每 5 秒 `curl -sS http://127.0.0.1:8081/api/jobs` 观察 `progress.days` 增长直至 `SUCCEEDED`，并在浏览器里确认新会话出现在列表且可下钻。

- [ ] **Step 7: 提交**

```bash
git add README.md docs/superpowers/specs/2026-10-14-web-triggered-runs-design.md
git commit -m "docs: document web-triggered runs and the new default startup"
```

---

## 自查记录

**Spec 覆盖核对**（逐节 → 任务）：

| Spec | 任务 |
|---|---|
| §4.1 `RunOptions` / `JobRequest` / `JobState` / `Job` / `RunLauncher` / `JobService` / `ModelKeyGuard` / `BenchmarkRunLauncher` | T1 / T5 / T5 / T5 / T5 / T7+T8 / T6 / T9 |
| §4.2 `RunOptions` / `SecretsFileLoader` / `EffectiveEnvironment` / `LlmClientProviders` | T1 / T2 / T2 / T3 |
| §4.3 改动表 8 个既有类 + `ProviderResolver` | T1（ConfigurationMerger, CliRunOptions）/ T3（ApplicationWiring, ProviderResolver）/ T4（RunComponentFactory）/ T10-T13（ApiRouter）/ T11-T12（DashboardServer）/ T14（ServeCommand）/ T15（BenchmarkCommand）；`ApiException` T7；`ServeOptions`+`DashboardLauncher` T14；`ModelCatalogService` T11 |
| §4.4 五个端点 + 状态码机制 + Origin + body 上限 | T10（分发骨架与 405/404）/ T11（models）/ T12（POST + 403 + 413）/ T13（GET/DELETE + 409） |
| §4.5 前端两个面板 + `boot()` 重构 + `apiSend` | T16（表单 + boot + apiSend）/ T17（队列面板 + 轮询） |
| §4.6 `--log-dir` 冲突解法 | T14（`ServeOptions` 刻意不含 `--log-dir`） |
| §5.1 入口语义 + `EcommerceBenchApplication` 不改 | T15 |
| §5.2 错误处理（不回显 body、只留 SimpleName+首行） | T5（`Job.describe`）/ T12（`readBody` 不回显） |
| §5.3 降级（`.env` 缺失、进行中会话 404） | T2 / T16（无密钥文案） |
| §5.4-5.5 取消语义与竞态 | T7（`cancel` + `Job` 的 synchronized 迁移）/ T12（`DashboardServer.close` 调 `jobs.shutdown()`） |
| §6 测试策略 9 项 | T2/T3/T4/T5/T6/T7/T8/T11/T12/T13/T15 逐一对应 |
| §7 门禁基线 | 每个任务的「全量门禁」步骤 + T18 Step 5-6 |
| §8 编码约定 | Global Constraints |
| §9 文档改动 | T2 Step 9（`.gitignore`）/ T18（README） |

**已知遗留（不在本计划范围）**：spec §10 列出的 S1 残余项、S3 导出能力、`ProviderResolver` 错误提示缺陷、阶段 B 的浏览器桌面版验收与学习维度真数据二轮验收。

**计划编写期间发现并已写进任务的两处陷阱**（执行者若跳过会静默出错）：
1. `DashboardServerTest` 若把 `models_config.json` 写在 `@TempDir log` 下，`SessionScanner` 会把它当成一个会话目录（它只过滤 `.` 前缀），导致 `listsDiscoveredSessions` 莫名多出会话 —— T11 Step 7 要求用第二个 `@TempDir`。
2. `JobRequest.seed` 若硬编码 42，会与 `ConfigurationMerger.DEFAULT_SEED` 形成两处默认值来源 —— T5 的开头约束与 `defaultsMatchCliRunOptionsValueForValue` 测试（断言 `seed()` 为 null）共同锁死这一点。
