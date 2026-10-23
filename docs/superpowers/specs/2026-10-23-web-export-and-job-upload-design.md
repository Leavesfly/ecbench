# Web 导出产物与 job 文件上传设计

日期：2026-10-23
状态：待用户评审
前置：`docs/superpowers/specs/2026-10-14-web-triggered-runs-design.md`（Web 触发 run，已实现）

## 1. 背景与目标

Web 看板已能触发 run、看对比/画像/曲线/谈判，但相对 CLI 仍有三处能力缺口，导致出论文图或做数据交付时必须切回命令行：

1. **PNG 图**：`evaluate plot` 与 `evaluate profile --output` 产 XChart PNG；`BalancePlotter`/`RadarPlotter` 全仓仅被 `EvaluationCommand` 引用，Web 侧零引用，前端只有自绘 SVG。
2. **文件导出**：`evaluate profile --ranking-output` 产排名 JSON/CSV；`resources/web/` 内 `download|export|createObjectURL|toDataURL|blob` 零命中，`StaticAssets` 白名单只有三个前端文件，看板只能看、拿不走文件。
3. **预构建 job JSONL**：`JobRequest.parse` 硬性拒绝 body 里的 `jobFile`，`--job-file` 这条 CLI 能力从 Web 完全不可达。

目标：把这三项搬到 Web，使看板在"跑基准 + 出图 + 出数据"上成为 CLI 的功能超集，同时**不改动任何既有端点契约、不改动 CLI 行为**。

## 2. 范围

### 纳入

- 余额曲线 PNG、七轴雷达 PNG 的服务端渲染与下载。
- 排名表 CSV / JSON 下载。
- 指标矩阵 CSV 下载（长表 tidy 格式）。
- `POST /api/jobs` 接受可选的 job JSONL **内容**上传。
- 前端下载入口与文件选择控件。

### 明确不做（非目标）

| 项 | 理由 |
|---|---|
| 原始产物端点（`negotiation_metrics.json`、`negotiation_records.jsonl`、`messages.jsonl`、`output.log`） | 用户未选"排障复盘"用途 |
| chatbox 原始 jsonl 导出 | 同上；顺带说明：Web chatbox 端点每条消息只留 `role`/`content` 的有损问题本次**不修** |
| 导出 Web 独有视图（六维度诊断图、排名交叉表、三类诊断图） | 用户未选；且无现成绘图类，成本量级不同 |
| 给 CLI `evaluate compare` 补 `--output` | 用户选择矩阵 CSV 为 Web 独有能力 |
| 放宽 64 KB 请求体上限 | 用户已定：保持不变 |
| CSV 公式注入转义（`=`/`+`/`-`/`@` 前缀防护） | 指标标签来自冻结的 `METRIC_DEFS` 固定集合，无一以这些字符开头 |
| 修复 `mvn clean verify` 的 spotless 存量红灯 | 独立任务，见 §9 |

## 3. 现状取证（代码事实）

以下均经读码或 `javap` 实测，是设计成立的前提：

- `BitmapEncoder` 在 xchart-4.0.4 中提供 `getBitmapBytes(IChart, BitmapFormat)` 与 `saveBitmap(IChart, OutputStream, BitmapFormat)`，**服务端出 PNG 字节流无需落盘**。
- `BalancePlotter` 与 `RadarPlotter` 的静态块均已 `System.setProperty("java.awt.headless", "true")`，服务端渲染前提早已满足。
- `RankingTable.toCsv(profile)`、`toJson(profile, mapper)`、`toText(profile)` **已直出 String**，导出侧零改动。
- `SessionComparison(String name, int runCount, List<MetricStat> metrics, SessionLearning learning)` **没有 model 字段**；`MetricStat(String label, Double mean, Double std, int n, String fallback)`。
- `RunComponentFactory.resolveJob` 只取 `jobs.get(0)`——**多行 job JSONL 里只有第一行真正被用**，其余 run 复用同一份。
- `RunConfig.jobFile` 类型为 `Path`，由 `ConfigurationMerger` 经 `paths.optional(cli.jobFile())` 解析。
- `JobFileLoader.load(Path)` 先 `Files.readAllLines` 再按行解析，任一行失败抛含 1 基行号的异常；解析循环天然按 `List<String>` 走。
- `SessionScanner.runIndexes(Path)` 当前为 private，run 序号来自 `metrics/run_<i>_analysis.json`；余额 CSV 在 `balance/` 下，**两者可能不同步**。
- `BalancePlotter.seriesName(csv)` 从文件名剥 `_daily_balance.csv` 得到 series 名，多个会话的 `run_0` 会**同名冲突**。
- `ApiRouter.write()` 只写 `application/json`；`StaticAssets.respond(exchange, status, type, byte[])` 已是通用响应器，但为 `private static`。
- `RunLayoutResolver.RunLayout` 提供 `balanceCsv(int)`、`messagesJsonl(int)`、`analysisJson(int)`、`negotiationMetricsJson(int)`、`negotiationRecordsJsonl(int)`，并兼容 organized 与 legacy 两种布局。
- 18 个 tool_schema 压缩后合计 13,699 字节，故单行 job 约 14 KB，**装得进现有 64 KB 上限**。
- `ecbench-app/src/test/.../web/` 下**不存在 `ApiRouterTest`**，路由与状态码测试全部通过 `DashboardServerTest` 起真实 HTTP 完成。（另一条旧记忆称 `RunComponentFactoryTest` 也不存在，**已过时**：本次列目录核实它已在 `app/run/` 下。）
- 已核实本次点名的测试文件均存在：`BalancePlotterTest`、`RadarPlotterTest`、`RankingTableTest`、`SessionScannerTest`、`StaticAssetsTest`、`CompareServiceTest`、`JobFileLoaderTest`、`JobRequestTest`、`JobServiceTest`、`DashboardServerTest`；`ComparisonCsvWriterTest` 与 `ExportServiceTest` 为本次新增。

## 4. API 契约

### 4.1 新增导出端点

全部为 GET、只读，因此**不做 Origin 校验**（与现有 GET 端点一致）。

| 端点 | 参数 | Content-Type | 下载文件名 |
|---|---|---|---|
| `GET /api/export/balance.png` | `sessions`（逗号分隔）、`title`（可选） | `image/png` | `balance.png` |
| `GET /api/export/radar.png` | 同上 | `image/png` | `radar.png` |
| `GET /api/export/ranking.csv` | `sessions` | `text/csv; charset=utf-8` | `ranking.csv` |
| `GET /api/export/ranking.json` | `sessions` | `application/json; charset=utf-8` | `ranking.json` |
| `GET /api/export/matrix.csv` | `sessions` | `text/csv; charset=utf-8` | `matrix.csv` |

约定：

- **`sessions` 缺省语义与 `/api/compare` 完全一致**：未指定时取扫描序前 `MAX_AUTO_SESSIONS`（12）个会话。不另立规则。
- **下载文件名一律为固定 ASCII 常量**，绝不把会话名、模型键或 `title` 拼进 `Content-Disposition`——那会构成 CRLF 头注入面。
- `title` 为空时沿用各 plotter 既有默认串：`BalancePlotter` 为 `"daily balance"`，`RadarPlotter` 为 `"model capability profile"`。
- 会话名解析统一走 `SessionScanner.sessionDir(name)`，继承其防穿越校验（非法名 → 400，不存在 → 404，且解析结果必须仍落在 logDir 内）。

### 4.2 余额 PNG 的 run 枚举

对每个会话：取 `runIndexes(sessionDir)` 得到升序 run 序号，再逐个用 `Files.exists(layout.balanceCsv(i))` 过滤，只保留真实存在的余额 CSV。

**不得只信 `SessionInfo.runCount()`**：run 序号来源于 `metrics/`，余额文件在 `balance/`，两者可能不同步（例如 run 中途失败）。全部会话过滤后 CSV 列表为空 → 404。

series 标签由 ExportService 显式构造为 `"{会话名}/run_{i}"`，解决 §3 所述的同名冲突。

### 4.3 雷达 PNG 的数据来源

复用与 `/api/compare` 的 `radar` 段**完全相同**的计算链：`RunComparator.compare(dirs)` → `CrossModelProfiler.profile(report)` → `RadarPlotter.png(profile, title)`。退化情形（单会话、min-max 归一化不可算、学习轴为 null）的表现与屏幕上的雷达图一致，不另行处理。

### 4.4 指标矩阵 CSV（长表 tidy）

列固定为：`session,metric,mean,std,n`

- 一行一个 (会话, 指标) 对；行序 = 会话序 × 该会话 `metrics()` 的原始顺序。
- **不含 `model` 列**：`SessionComparison` 无该字段，而"会话名最后一个下划线段即模型键"是 app 层的命名约定（`SessionScanner.modelKey`），把它塞进 evaluation 模块属错误分层。
- `mean` 为 `null` 时，`mean` 与 `std` 两格留空；`n` 照常输出。
- `MetricStat.fallback`（`"n/a"` 一类展示占位）**有意不导出**。
- 数值格式统一 `%.6f`（高于 UI 的 4 位，便于二次分析）。
- 用 evaluation 已有的 commons-csv `CSVPrinter` 输出，引号转义交给它。
- **绝不触碰 `RunComparator.METRIC_DEFS`**：那是与 Python `compare_runs` 对齐的冻结契约，加行会改变 CLI 输出表形状并可能打破 `PythonCompatibilityTest` / `AnalysisGoldenParityTest`。新写入器只读 `ComparisonReport`。

### 4.5 job JSONL 上传

`POST /api/jobs` 的 body 新增**可选**字段 `jobFileContent`（字符串）。

- 缺省或为 `null` → 行为与现在逐字相同（`jobFile` 取服务进程启动配置）。
- 非字符串、或 trim 后为空 → 400。
- 整个 body 仍受 `MAX_BODY_BYTES = 65_536` 约束，超出 → 413。**413 文案必须写明上限，并提示"`resolveJob` 只使用文件第一行，可只上传首个 job"**——这是 §3 探到的真实事实，能让用户自救。
- JSONL 某行解析失败 → 400，带上 `JobFileLoader` 已有的 1 基行号。**这要求提交期预校验**，见下条。
- **解析时机与预校验（关键）**：真正的解析发生在 `RunComponentFactory.sharedFileJobs` 的惰性调用里，即 run 执行期、job 线程上——那时失败只能变成 job FAILED，**拿不到 400**。因此 `JobService.submit` 必须先用 `new JobFileLoader(objectMapper, RunConfig.defaults()).parseLines(lines)` 做一次预校验，把畸形内容在提交期就挡成 400。用 `RunConfig.defaults()` 作 fallback 是安全的：该 fallback 只在 job 行省略上限时提供缺省值，**不影响解析成败**。执行期 `RunComponentFactory` 仍按既有链路用合并后的 `RunConfig` 重新解析一次（重复解析成本可忽略）。
- **覆盖优先级**：`jobFileContent` 存在时**覆盖**服务进程启动时的 `--job-file`；两者都不存在时 `jobFile` 为 null，`resolveJob` 返回 null，Agent 使用 defaultJob（与现状一致）。
- 服务端**不接受任何路径**：用户提交的是内容，临时文件路径由服务端自己生成。这与既定决策"HTTP 不接受路径类参数（logDir/dataDir/tokenizerPath/jobFile）"**不冲突**——该决策防的是任意路径读写入口，而此处路径不由外部控制。
- 其余 3 个路径类字段（`logDir`/`dataDir`/`tokenizerPath`）继续一律取服务进程配置，不接受 body 输入。

## 5. 组件与改动清单

全部为加法式改动，既有公开方法行为不变。

### 5.1 ecbench-evaluation

| 单元 | 改动 |
|---|---|
| `BalancePlotter` | 抽出 `private XYChart buildChart(List<Path> csvFiles, List<String> labels, String title)`；新增 `public byte[] png(List<Path> csvFiles, List<String> labels, String title)`（内部 `BitmapEncoder.getBitmapBytes(chart, BitmapFormat.PNG)`，`IOException` 包成 `UncheckedIOException`）；`csvFiles` 为空或 `labels.size() != csvFiles.size()` → `IllegalArgumentException`；现有 `plot(csvFiles, options)` 改为调用 `buildChart`，标签仍由 `seriesName(csv)` 推导，**输出行为逐字不变** |
| `RadarPlotter` | 同范式：抽出 `private RadarChart buildChart(NormalizedProfile profile, String title)`，新增 `public byte[] png(NormalizedProfile profile, String title)`；`plot()` 委托 `buildChart` 后行为不变 |
| `ComparisonCsvWriter`（新增） | `public String toLongCsv(ComparisonReport report)`，契约见 §4.4 |

缩进沿用本模块的 4 空格。

### 5.2 ecbench-app / app.web

| 单元 | 改动 |
|---|---|
| `HttpResponses`（新增） | 从 `StaticAssets.respond` 抽出的共享响应工具：`static void respond(HttpExchange, int status, String contentType, byte[] body)` 与 `static void attachment(HttpExchange, int status, String contentType, String filename, byte[] body)`（后者额外设 `Content-Disposition: attachment; filename="…"`，filename 只接受调用方传入的 ASCII 常量） |
| `StaticAssets` | 改为委托 `HttpResponses`，删除自身私有副本；对外行为不变 |
| `ExportService`（新增） | 构造器注入 `ObjectMapper`、`SessionScanner`、`CompareService`、`RunLayoutResolver`。对外五个方法：`balancePng(List<String> sessions, String title)`、`radarPng(…)`、`rankingCsv(…)`、`rankingJson(…)`、`matrixCsv(…)`，统一返回 `ExportService.Export(String contentType, String filename, byte[] body)` record；由 String 产出的三种（ranking.csv/json、matrix.csv）以 UTF-8 取字节。**每个方法必须先用 `SessionScanner.sessionDir` 解析完全部会话名再碰绘图/计算类**，让非法名（400）与不存在（404）自然冒泡；随后调用绘图类的部分包在 try 中，**只把绘图类抛的 `IllegalArgumentException` 转译为 `ArtifactNotFoundException`（404）** |
| `CompareService` | 新增 `public record Compared(ComparisonReport report, NormalizedProfile profile)` 与 `public Compared compareAll(List<Path> sessionDirs)`，把"`RunComparator.compare` → `CrossModelProfiler.profile`"这两步收敛为唯一一处；现有 `compare(dirs)` 改为先取 `compareAll(dirs)` 再序列化，JSON 输出零变化。**不采用只暴露 `profile(dirs)` 的写法**：导出侧矩阵 CSV 需要 `report`、雷达与排名需要 `profile`，若只给 `profile(dirs)` 则矩阵导出得重跑一次 `RunComparator`；返回两者的 record 让两侧都只算一次 |
| `SessionScanner` | `runIndexes(Path)` 由 private 提为 `public`，签名与返回不变 |
| `ApiRouter` | `route()` 增加 `/api/export/` 前缀分支：先 `requireGet`，再按 kind 分发到 `ExportService`，用 `HttpResponses.attachment` 写出；未知 kind → 404（与现有"未知端点"一致）。构造器新增 `ExportService` 形参 |
| `DashboardServer` | 装配 `ExportService` 并传给 `ApiRouter` |

注意 `ApiRouter.route()` 目前约 45 行，加分支后仍远低于 Checkstyle 的 MethodLength ≤ 150。

### 5.3 ecbench-app / app.run 与 app.job

| 单元 | 改动 |
|---|---|
| `JobFileLoader` | 抽出 `public List<RunJob> parseLines(List<String> lines)`；`load(Path)` 改为读文件后委托 `parseLines`。坏行异常与 1 基行号行为不变。**方法名必须叫 `parseLines` 而不是 `parse`**：该类已有一个 `private RunJob parse(JsonNode root)`，同名重载会让两个语义完全不同的 `parse` 共存 |
| `JobRequest` | 新增三参重载 `parse(JsonNode body, RunOptions server, String jobFileOverride)`；现有两参版委托它并传 `server.jobFile()`。record 组件不变 |
| `JobService` | `submit(body)` 中：若 `jobFileContent` 存在，先 `Files.createTempFile("ecbench-jobs-", ".jsonl")` 并以 UTF-8 写入内容，用 `JobFileLoader.parseLines(lines)` 做提交期预校验（见 §4.5），再把该路径作为 override 传给 `JobRequest.parse`；把临时文件路径记在 `Job` 上，`execute(job)` 的 `finally` 中删除；**预校验失败、`ModelKeyGuard.requireReady` 报错、队列已满这三条提交前异常路径都要删掉已建的临时文件**；`shutdown()` 兜底清扫所有未删的临时文件（注意：该类现有的关停方法名是 `shutdown()`，**不是** `close()`，它未实现 `AutoCloseable`） |
| `Job` | 增加一个可空的临时 job 文件路径字段与对应访问器，仅供清理使用 |

**domain 与 `ConfigurationMerger` 零改动**——`RunConfig.jobFile` 仍是 `Path`，上传内容经临时文件落地后走的是与 CLI 完全相同的装配链。

### 5.4 前端（resources/web，2 空格缩进）

- `index.html`：在对比区新增"导出"面板，含 5 个 `<a download>` 链接；在"新建运行"表单里新增 `<input type="file" accept=".jsonl">` 与一行中文提示（说明上限、以及只有第一行会被使用）。
- `app.js`：
  - 导出链接的 `href` 在**勾选集合变化时重算**，`sessions` 参数用 `encodeURIComponent` 逐个编码后逗号拼接；无勾选时按缺省语义留空 `sessions`（后端取前 12 个）。
  - **纯 `<a href download>`，不引入 fetch、不新增 JS 状态、不用 `createObjectURL`**——因此不涉及跨域与内存回收问题。
  - job 文件用 `FileReader.readAsText` 读取；**客户端先按 60 KB 预检**（因为 64 KB 是整个 body 的预算，要与其余 9 个字段共享），超限直接给中文提示、不发请求。
- `app.css`：导出面板样式，复用既有卡片类。

不新增静态资源文件——`StaticAssets` 白名单只有 `index.html`/`app.js`/`app.css`，新增文件会被 404。

## 6. 数据流

```
浏览器 <a href download>  ──GET /api/export/*──▶ ApiRouter ──▶ ExportService ──┬─▶ BalancePlotter.png(csvFiles, labels, title)
   （无 fetch、无 JS 状态）                                    │               └─▶ RadarPlotter.png(profile, title)
                                                              ├─▶ CompareService.compareAll(dirs)   ← 新增，收敛既有两步并同时返回 report + profile
                                                              ├─▶ RankingTable.toCsv / toJson       ← 现成，零改动
                                                              └─▶ ComparisonCsvWriter.toLongCsv     ← 新写

浏览器 <input type=file>  ──POST /api/jobs {jobFileContent}──▶ ApiRouter ──▶ JobService
                                                              ├─ Origin 校验（既有）
                                                              ├─ readBody（既有 64 KB 上限）
                                                              ├─ Files.createTempFile → 写入内容
                                                              ├─ JobRequest.parse(body, server, tempPath)
                                                              ├─ ConfigurationMerger.merge（既有，零改动）
                                                              └─ BenchmarkRunLauncher → RunCoordinator（既有）
                                                                    └─ finally: 删除临时文件
```

## 7. 错误处理

复用现有映射，**不新增状态码**：

| 情形 | 状态码 |
|---|---|
| 会话名非法（含 `..`、斜杠、非法字符） | 400 |
| `jobFileContent` 非字符串或空白 | 400 |
| job JSONL 某行解析失败（带 1 基行号） | 400 |
| 会话不存在 | 404 |
| 无可绘余额 CSV（全部 run 过滤后为空） | 404 |
| 无可绘模型画像（`NormalizedProfile.models()` 为空） | 404 |
| 未知 export kind | 404 |
| 已知 export 路径但非 GET | 405 |
| body 超 64 KB | 413 |
| 其余 RuntimeException | 500 |

三处实现细节必须遵守：

- `ApiException` 的 catch 分支**必须排在 `RuntimeException` 之前**（现有顺序已正确，新增分支不得打乱）。
- "无可绘数据"要抛 `ArtifactNotFoundException`（→404）而不是 `IllegalArgumentException`（→400），因为请求本身合法、只是数据缺失。绘图类现有的 `IllegalArgumentException("没有可绘制的余额 CSV" / "没有可绘制的模型画像")` 由 `ExportService` 捕获并转译为 404，**不修改 evaluation 模块的既有异常类型**。
- **转译范围必须限定**：`ExportService` 只在调用绘图/计算类的那一段捕 `IllegalArgumentException`；会话名解析（`SessionScanner.sessionDir`）必须在该 try **之外**先完成，否则非法会话名会被误转译成 404 而丢失 400 语义。

## 8. 安全与已接受的取舍

- **导出端点全为 GET 且只读**，不引入新的写入面；会话名解析继承 `SessionScanner` 既有防穿越校验。
- **`Content-Disposition` 只用固定 ASCII 常量**，杜绝头注入。
- **上传 job 内容的安全面变化（已确认接受，从简处理）**：`jobFileContent` 含 `messages`（system/user 提示词）与 `tool_schemas`，因此 HTTP 可控范围从"9 个数值/枚举字段"扩大到"agent 的提示词与工具定义"。防护**沿用既有的 Origin 校验**（期望 origin 由服务端已绑定端口构造，不信 `Host` 头，校验在读 body 之前完成），不新增验证层。
  - 前提：单进程、本地回环、单用户工具。
  - **失效条件**：一旦出现多用户或远程访问，此取舍连同 env + `.env` 的密钥模型都不再充分，必须换成正式鉴权后重新评估。
- 临时文件由 `Files.createTempFile` 在系统临时目录生成，路径不受外部控制；终态、校验失败、`close()` 三处均有删除兜底。

## 9. 测试计划与门禁

### 测试

| 目标 | 用例 |
|---|---|
| `BalancePlotterTest` | `png()` 返回非空且以 PNG 魔数 `\x89PNG` 开头；显式标签下多会话 `run_0` 不再同名；**既有 `plot()` 用例一字不改地通过** |
| `RadarPlotterTest` | `png()` 返回非空且以 PNG 魔数开头；`models()` 为空仍抛既有 `IllegalArgumentException`；既有 `plot()` 用例不变 |
| `ComparisonCsvWriterTest`（新增） | 行数 = 会话数 × 指标数；行序为会话序 × 指标序；`mean` 为 null → `mean`/`std` 两格空而 `n` 照出；含逗号的标签被正确引号包裹；表头逐字为 `session,metric,mean,std,n` |
| `SessionScannerTest` | `runIndexes` 公开后返回升序序号；`metrics/` 缺失时返回空列表 |
| `ExportServiceTest`（新增） | 多会话时 series 标签为 `"{会话名}/run_{i}"` 且不重名；`balance/` 与 `metrics/` 不同步时只取真实存在的余额 CSV；全为空时抛 `ArtifactNotFoundException`；非法会话名仍抛 `IllegalArgumentException`（**锁死不被误转译**）；`Content-Disposition` 文件名为固定 ASCII 常量 |
| `StaticAssetsTest` | 抽到 `HttpResponses` 后既有用例原样通过（行为不变的回归锚） |
| `CompareServiceTest` | 抽出 `compareAll(dirs)` 后既有 JSON 输出用例原样通过；新增一例断言 `Compared` 的 report 与 profile 同源于一条链 |
| `JobFileLoaderTest` | `parseLines(lines)` 与 `load(path)` 结果一致；坏行仍抛含 1 基行号的异常 |
| `JobRequestTest` | 三参重载的 override 生效；两参版仍取 `server.jobFile()`；`jobFileContent` 空白 → 400 语义的 `IllegalArgumentException` |
| `JobServiceTest` | 上传 happy path；**断言临时文件在终态后被删**；预校验失败 → 400 语义异常 **且临时文件已删**；`shutdown()` 清扫残留 |
| `DashboardServerTest`（既有真实 HTTP 模式） | 5 个导出端点各测 200 + 正确 Content-Type + `Content-Disposition`；400 非法会话名；404 会话不存在；404 无余额数据；405 POST 到导出端点；404 未知 kind；job 上传端到端；413 超限且文案含上限与"只用第一行"提示 |
| 前端 | 沿用仓库既有的 node DOM 桩冒烟验渲染与 href 重算逻辑；**另需一次真实浏览器验收**（CSS 渲染与溢出，DOM 桩验不了） |

### 门禁（实测口径）

- `mvn -o clean test`：全模块单测，主门禁，必须全绿。
- `mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`：所改模块风格门，须 0 违规。
- **不要用 `mvn clean verify` 作为门禁**：spotless 绑在 verify 阶段，根 pom 的 `<googleJavaFormat/>` 未设 `<style>AOSP</style>`，默认 2 空格与仓库既有 4 空格模块冲突，`spotless:check` 在 evaluation 模块即报 20+ 文件存量违规。**严禁用 `mvn spotless:apply` 修复**——它会重排整模块既有文件、产生巨量无关 diff。根级 `mvn checkstyle:check` 同为存量红（opponent 1 处 + llm 4 处）。
- Checkstyle 实际生效规则：AvoidStarImport、FileTabCharacter、LineLength ≤ 120、命名类规则、MethodLength ≤ 150。无 ImportOrder、无 UnusedImports，故**多导入未使用的类不会被门禁拦下，需自查**。
- 含中文的行按**字符数**而非字节数量行宽（`awk`/`wc` 按字节统计会给出约 3 倍虚高值而误判超限）。
- JaCoCo ≥ 85% 行覆盖率仅施加于 domain / simulation / opponent，本次不涉及。
- 缩进：evaluation / app 为 4 空格，`resources/web` 前端三件套为 2 空格，新文件镜像所在模块既有风格。

### 验收

1. 全量单测绿 + 两个模块 checkstyle 0 违规。
2. `mvn -o -DskipTests clean package` 重打 fat jar，起 `serve`，用 `curl -sS -D - -o <body文件>` 逐个端点核对状态码、`Content-Type`、`Content-Length` 与 body 字节数一致。
3. 真实浏览器点开 5 个下载链接，确认文件落地且 PNG 可打开、CSV 可被表格软件正确解析。
4. CLI 回归：`evaluate plot` / `evaluate profile` / `evaluate compare` / `--job-file` 四条命令输出与改动前逐字一致。

## 10. 实施顺序建议

按依赖方向自内向外，每步可独立验证：

1. evaluation：两个 plotter 的 `buildChart` 抽取 + `png()` 重载（既有测试须原样通过）。
2. evaluation：`ComparisonCsvWriter` + 测试。
3. app.web：`HttpResponses` 抽取 + `StaticAssets` 委托（行为不变，既有测试须原样通过）。
4. app.web：`SessionScanner.runIndexes` 提公开 + `CompareService.compareAll` 抽取。
5. app.web：`ExportService` + `ApiRouter` 分支 + `DashboardServer` 装配 + 真实 HTTP 测试。
6. app.run / app.job：`JobFileLoader.parseLines` 抽取 → `JobRequest` 三参重载 → `JobService` 临时文件生命周期。
7. 前端三件套：导出面板 + 文件上传控件。
8. README 更新（导出端点、上传上限与"只用第一行"的事实）。
