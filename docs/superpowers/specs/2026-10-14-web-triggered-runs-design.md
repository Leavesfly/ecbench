# Web 触发运行 + 默认以 Web 服务启动 设计

- 日期：2026-10-14
- 状态：已实施（实施计划与逐步验收记录见 `docs/superpowers/plans/2026-10-14-web-triggered-runs.md`）
- 前置：`docs/superpowers/specs/2026-10-10-serve-web-dashboard-design.md`、`docs/superpowers/specs/2026-10-12-paper-capability-dashboard-design.md`（serve 看板与论文六维视图均已实现并在 main 上）
- 范围：两个按序交付的增量
  1. **增量 1**：把「跑一次基准」的能力开放到 Web 层——提交运行、查询状态与进度、取消
  2. **增量 2**：把项目默认启动形态从「跑批处理」改为「起 Web 服务」，同时保持既有 CLI 调用逐字可用

本设计推翻旧 spec §2 的非目标「不在页面上发起 run、不做写操作」（即内部代号 S4，此前被明确排除）。推翻理由：用户明确要求。

## 1. 背景：三条硬约束（均已核验）

| # | 约束 | 证据 | 设计后果 |
|---|---|---|---|
| ① | `RunComponentFactory` 是「一次配置、终身缓存」 | `catalog`/`guidance`/`modelRegistry`/`tokenCounter`/**`runDirectory`**/`fileJobs` 是实例字段，`synchronized` 惰性初始化且「第一个 options 胜出」；`sharedRunDirectory()` 用时间戳建会话目录，只建一次。而 `ApplicationWiring` 把它注册为 Spring 单例 | Web 层若复用该单例跑第二个 job，**产物会写进第一个 job 的会话目录**，并静默沿用第一个 job 的 data-dir / models_config / tokenizer 路径。→ 每个 job 必须持有独立 factory 实例 |
| ② | HTTP 线程池只有 4 个线程，run 绝不能跑在上面 | `DashboardServer.WORKER_THREADS = 4`；`RunCoordinator.run()` 阻塞，finally 里 `awaitTermination(5 分钟)`；一个 365 天 episode 是小时级任务 | 必须有独立的 job 执行器；进度只能靠轮询，不能靠长连接（SSE 会长期占用 4 线程之一，几个标签页就拖垮只读 API） |
| ③ | API key 在装配期固化进 bean | `ApplicationWiring.llmClientProvider()` 用 `System.getenv()` 构造 `ProviderResolver`，只构造一次 | 密钥必须在服务启动前就位；来源为「进程环境变量 + 本地密钥文件」，不经 HTTP 传输 |

补充事实：`ecbench-agent` 全模块 grep `InterruptedException|isInterrupted|currentThread().interrupt` **0 命中**，agent 主循环完全不检查中断。取消因此只能是「尽力而为」，见 §5.4。

## 2. 非目标（YAGNI）

- 不做鉴权、不做数据库、不外网暴露：服务仍默认只绑 `127.0.0.1`。
- 不做多 job 并行：全局单线程串行队列（job 内部仍可用 `runs` 参数并行多个 run，`RunCoordinator` 既有能力）。
- 不做 job 状态落盘：仅内存，服务重启后 job 列表清空（历史产物仍在 `log/` 下，会话扫描照常可见）。
- 不做 SSE / WebSocket：进度靠前端轮询 + 从已落盘产物推导。
- 不做运行中产物的实时下钻：进行中的会话被下钻时沿用既有 404 分支。
- 不改任何产物格式：`analysis.json` / `daily_balance.csv` / `messages.jsonl` / `negotiation_*.json(l)` 字段与取值零改动，不新增产物文件。
- 不改 `RunComparator.METRIC_DEFS`（29 行，与 Python `compare_runs` 对齐的冻结契约）。
- 不引入前端框架、构建步骤或外部图表库（继续零依赖原生 JS + SVG）。
- 不引入嵌入式 Web 容器：继续用 JDK `com.sun.net.httpserver.HttpServer`，`WebApplicationType.NONE` 不变。
- 不改造 Picocli 的 bean 注入机制（不引入 `IFactory`）；`ServeCommand` 仍由反射实例化。
- 不写 JS 单测（沿用既有裁定：前端以浏览器人工验证 + 控制台零报错为准）。

## 3. 已裁定的设计决策

| 决策点 | 裁定 |
|---|---|
| 入口语义 | 无参数 → 起 Dashboard；带 `--model` → 仍跑批处理。README 既有命令行逐字可用 |
| HTTP 栈 | 继续 JDK HttpServer，只把「非 GET → 405」改成按 (方法, 路径) 分发 |
| 密钥来源 | 进程环境变量 + 仓库根 `.env`（不入库）；**真实 env 优先，`.env` 只填补缺失项** |
| 并发模型 | 单 job 串行队列，队列上限 8（超出 → 409）；支持取消 |
| 进度反馈 | 前端 2.5 秒轮询 `GET /api/jobs`，仅在存在非终态 job 时轮询 |
| 交付范围 | API + 前端「新建运行」表单与「任务队列」面板 |
| 状态持久化 | 仅内存 |
| 路径类参数 | `logDir`/`dataDir`/`tokenizerPath`/`jobFile` **不接受 HTTP 输入**，一律取服务进程配置 |
| 跨站防护 | `POST`/`DELETE` 校验 `Origin`：缺失或等于本服务 origin 才放行，否则 403 |

## 4. 增量 1：Web 触发运行

### 4.1 新增包 `io.github.ecommercebench.app.job`

依赖方向：`web → job → config/run`；`job` 不反向依赖 `web`。
本包含 7 个单元；§4.2 的四个单元属于 `app.config`，不在本包内。

| 单元 | 类型 | 职责 | 依赖 |
|---|---|---|---|
| `JobRequest` | record `implements RunOptions` | 一次提交的全部参数 | `RunOptions` |
| `JobState` | enum | `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED` | 无 |
| `Job` | 可变类 | `id`、`request`、`volatile` 状态、`submittedAt`/`startedAt`/`finishedAt`、`sessionName`、`error`、`Future` 句柄 | `JobState`、`JobRequest` |
| `RunLauncher` | 函数式接口 | `List<RunOutcome> launch(BenchmarkOptions options, Consumer<Path> onSessionCreated)` | `BenchmarkOptions` |
| `JobService` | 类 | 内存注册表 + 单线程串行执行器 + 状态机 + 取消 + 队列上限 + 进度推导；**并负责把 `Job` 序列化为 `JobView` JSON**（构造器注入 `ObjectMapper`，沿用 `CompareService`/`RunArtifactService` 的既有惯例） | `RunLauncher`、`ConfigurationMerger`、`ModelKeyGuard`、`ObjectMapper` |
| `ModelKeyGuard` | 类 | **密钥就绪判定的唯一实现点**：`missingEnv(modelKey)` / `npcMissingEnv()` / `requireReady(modelKey)`。`JobService`（提交拦截）与 `ModelCatalogService`（列表标注）**共用它**，避免两处各写一套推断而漂移 | `ModelRegistryLoader`、`ProviderResolver`、`EffectiveEnvironment` |
| `BenchmarkRunLauncher` | 类 `implements RunLauncher` | 生产实现：**每次 `launch` 新建一个 `RunComponentFactory`**（约束 ① 的解法）+ `RunCoordinator`，并把 `Consumer<Path>` 会话回调透传给工厂 | `ObjectMapper`、`LlmClientProvider` |

`RunLauncher` 的签名为 `List<RunOutcome> launch(BenchmarkOptions options, Consumer<Path> onSessionCreated)`——两个参数，会话回调必须能透到 `RunComponentFactory` 才能回填 `sessionName`。

`RunOptions` 的 13 个访问器（与 `CliRunOptions` 现有方法逐一同名同签名）：
`model()`、`maxTokens()`、`maxTurns()`、`maxDays()`、`initialBalance()`、`dailyFee()`、`maxTokenCapacity()`、`tokenizerPath()`、`logDir()`、`jobFile()`、`runs()`、`seed()`、`dataDir()`。

`JobRequest` 中**可由 HTTP 提交的 9 个字段**：`model`（必填）、`maxTokens`、`maxTurns`、`maxDays`、`initialBalance`、`dailyFee`、`maxTokenCapacity`、`runs`、`seed`。
**固定取服务端配置的 4 个字段**：`tokenizerPath`、`logDir`、`jobFile`、`dataDir`（构造时由 `JobService` 从服务进程配置填入，反序列化时忽略 body 里的同名字段）。

缺省值必须与 `CliRunOptions` 逐字相同：`maxTokens=16384`、`maxTurns=4000`、`maxDays=365`、`initialBalance=100000.0`、`dailyFee=50.0`、`maxTokenCapacity=128000`、`runs=1`、`seed=42`。

### 4.2 新增 `app.config` 四个单元

**`RunOptions`**（interface）：13 个只读访问器，成为 `ConfigurationMerger.merge()` 的新形参类型。放在 `app.config` 而非 `app.job`，因为 `ConfigurationMerger`（`app.config`）要依赖它，而 `app.config` 不应反向依赖 `app.job`。

**`SecretsFileLoader`**：读仓库根 `.env`，`KEY=VALUE` 逐行解析，忽略空行与 `#` 开头行，去掉值两侧成对的单/双引号。
**永不抛异常**：缺文件 / 空文件 / 畸形行 → 返回已解析部分，并在 stderr 记一行原因。catch 必须覆盖 `RuntimeException`（不只是 `IOException`）——本项目已在 `SupplierRosterReader` 上踩过：commons-csv 的 `UncheckedIOException` 是 `RuntimeException` 子类，穿透后使 serve 启动失败，推翻了「名册不可用时服务照常启动」的不变量。

**`EffectiveEnvironment`**：产出 `Map<String,String>` = `System.getenv()` 覆盖 `.env`（即**真实 env 优先，`.env` 只填补缺失项**，避免 `export` 被文件静默覆盖）。作为唯一的密钥视图，被三处共用：`ApplicationWiring.llmClientProvider()`、`ConfigurationMerger` 的 env 入参、`/api/models` 的 `keyReady` 判定。这是「表单说就绪、真跑却缺 key」漂移的根治点。

**`LlmClientProviders`**（静态工厂）：`fromEnvironment(Map<String,String> env, ObjectMapper mapper) -> LlmClientProvider`，把现内联在 `ApplicationWiring.llmClientProvider()` 里的 `ProviderResolver` + `RetryExecutor` + `LlmClientFactory` 组装逻辑搬出来，成为唯一构造点。`ApplicationWiring` 与 web/job 路径都调它。

`.env` 加入 `.gitignore`。

### 4.3 改动既有类（最小面）

| 类 | 改动 | 风险 |
|---|---|---|
| `ConfigurationMerger` | `merge(CliRunOptions, env, workingDir)` → `merge(RunOptions, env, workingDir)` | 纯类型放宽，零行为改动 |
| `CliRunOptions` | `implements RunOptions` | **零方法改动**，13 个访问器已存在 |
| `RunComponentFactory` | 新增构造重载 `(ObjectMapper, LlmClientProvider, Consumer<Path> onSessionCreated)`；在 `sharedRunDirectory()` 建目录后触发回调。既有 2 参构造委托为 no-op。**同时把 `sharedRunDirectory` 从 `private synchronized` 降为包级 `synchronized`**，作为测试切点 | 约 6 行 + 1 处可见性放宽；不改产物布局。这是把 `sessionName` 回传给 `Job` 的唯一侵入点 |
| `ApplicationWiring` | `llmClientProvider()` 改用 `EffectiveEnvironment`；provider 构造逻辑抽到新静态工厂 `LlmClientProviders.fromEnvironment(Map<String,String>, ObjectMapper)` | web/job 路径与 Spring 路径**共用同一处构造逻辑**，消除重复 |
| `ApiRouter` | 按 (方法, 路径) 分发表；新增 5 个端点；新增 `ApiException` → 任意状态码的映射分支 | 见 §4.4 |
| `DashboardServer` | 构造签名改为 `(Path logDir, String host, int port, Map<String,RosterEntry> roster, JobService jobs)`；为 job 配**独立的单线程执行器**（不复用 4 线程 HTTP 池）；`close()` 时中断在跑的 job 线程并有限等待。**全仓只有 2 个构造调用点**（`ServeCommand:53`、`DashboardServerTest:39`），改动面可控 | 见 §5.5 |
| `BenchmarkCommand` | `--model` 空白 → 走 `DashboardLauncher`（而非返回 2）；新增 `@Mixin ServeOptions` | 见 §4.6 |
| `ServeCommand` | 瘦身，`call()` 委托 `DashboardLauncher`；新增 `@Mixin ServeOptions`，保留自己的 `--log-dir`。**`resolveCatalog` / `resolveLogDir` 两个静态助手移到 `DashboardLauncher`**（否则两个命令入口会各有一份），`ServeCommandCatalogTest` 随之改名为 `DashboardLauncherPathTest` 并改调用目标 | 行为不变 |
| `ProviderResolver`（`ecbench-llm`） | **新增一个 public 访问器 `keyEnvironmentFor(String provider)`**，暴露既有 `PRESETS` 里的 provider → env 变量名映射 | 纯 additive，3 行，零行为改动。目的：`ModelKeyGuard` 对无 `apiKeyExpression` 的模型（openai/anthropic/google 系）取预设 env 名时**不重复抄一份映射表**。本改动**不修** §10 记的错误提示缺陷 |

**同时新增四个单元**（上表只列被改动的既有类）：

| 新单元 | 包 | 职责 |
|---|---|---|
| `ServeOptions` | `app.cli` | Picocli mixin，**只含 `--port` / `--host` / `--catalog`**（不含 `--log-dir`，避免与 `CliRunOptions` 重复声明而启动即抛），同时 mixin 进 `BenchmarkCommand` 与 `ServeCommand` |
| `DashboardLauncher` | `app.web` | 封装「解析 logDir → 校验目录 → 加载名册 → 装配 `JobService` → 启动 `DashboardServer` → 打印 URL → 阻塞至中断 → 返回退出码」，两个命令入口共用 |
| `ApiException` | `app.web` | 携带状态码的 `RuntimeException`，专用于 403 / 409 / 413 |
| `ModelCatalogService` | `app.web` | `GET /api/models` 的实现：用 `ModelRegistryLoader` 读注册表，逐项调 `ProviderResolver.resolve()` 并以是否抛 `ConfigurationException` 判定 `keyReady`，`missingEnv` 从 `${VAR}` 自行提取；额外输出 `npc` 段。构造器注入 `ObjectMapper` |

跨 job 复用同一个 `LlmClientProvider` 是安全的：`RunCoordinator` 今天就已经用 `min(runs, 核数)` 个线程**并发**调用同一 provider 的 `create()`，串行 job 之间复用不引入任何新的并发假设。有可变缓存的只有 `RunComponentFactory`，那个每 job 新建。

### 4.4 API 契约

分发表：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sessions`、`/api/compare`、`/api/sessions/{name}/{kind}` | 现状不变 |
| GET | `/api/models` | 新增 |
| GET | `/api/jobs`、`/api/jobs/{id}` | 新增 |
| POST | `/api/jobs` | 新增 |
| DELETE | `/api/jobs/{id}` | 新增 |

已知路径 + 不支持的方法 → 405；未知路径 → 404（保持现状）。既有测试 `DashboardServerTest.rejectsNonGetRequest`（POST `/api/sessions` → 405）**保持原样且仍绿**，因为 `/api/sessions` 没有 POST 处理器。

**状态码产生机制**（现有 `ApiRouter.handle` 只能映射 400/404/405/500，必须扩展）：

| 状态码 | 产生方式 |
|---|---|
| 400 | 抛 `IllegalArgumentException`（沿用现有映射） |
| 403 | 抛 `ApiException(403, …)` |
| 404 | 抛 `ArtifactNotFoundException`（沿用现有映射）；**未知 job id 也走这一条**，不新增异常类型 |
| 405 | 路由层直接返回（沿用） |
| 409 | 抛 `ApiException(409, …)` |
| 413 | 抛 `ApiException(413, …)` |
| 500 | 其余 `RuntimeException`（沿用） |

`ApiException extends RuntimeException`，携带 `int status`；`handle()` 的 catch 链中 **`ApiException` 必须排在 `RuntimeException` 之前**，否则被 500 吃掉。

**POST body 读取**：从 `exchange.getRequestBody()` 读取，**边读边计数**，超过 64 KB 立即停止读取并抛 `ApiException(413, …)`；无论成功失败均必须把请求体读干或关闭，否则 JDK HttpServer 会挂起该连接。`Origin` 校验在读取 body **之前**完成。

**`GET /api/models`** → 200

```json
{
  "models": [
    { "key": "qwen3.7-max", "provider": "openai-compatible",
      "modelName": "qwen3.7-max", "effort": null,
      "keyReady": true, "missingEnv": null }
  ],
  "npc": { "key": "npc_tools", "modelName": "gpt-4o-mini",
           "keyReady": false, "missingEnv": "OPENAI_API_KEY" }
}
```

`keyReady` 的判定方式：**复用 `ProviderResolver.resolve()` 是否抛 `ConfigurationException`**，不在 web 层重写密钥推断规则（`apiKeyExpression` 为空 → 取 provider 预设 env 名；否则按 `${VAR}` 展开）。`missingEnv` 从 `apiKeyExpression` 的 `${VAR}` 提取，无表达式时取 provider 预设名。

`npc` 段必须独立返回：`RunComponentFactory` 每个 run 都会额外 `llmClientProvider.create(registry.npcModel())`，默认 `npc_tools = gpt-4o-mini`（provider `openai` → `OPENAI_API_KEY`）。**即使主模型选 qwen，缺 `OPENAI_API_KEY` 一样跑不起来**，前端必须显式告警。

**`POST /api/jobs`** → 202

请求体（除 `model` 外全部可选）：

```json
{ "model": "qwen3.7-max", "maxDays": 10, "maxTurns": 50, "runs": 1, "seed": 42 }
```

响应：`{ "id": "<uuid>", "state": "QUEUED" }`

错误：
- 400 — JSON 解析失败 / `model` 缺失或空白 / 模型键不在注册表 / 数值非正或非整数 / **主模型或 NPC 密钥缺失（指明缺哪个变量）**
- 409 — 队列已满（上限 8，含正在跑的）
- 413 — body 超过 64 KB

**`GET /api/jobs`** → 200 `{ "jobs": [JobView, …] }`（按 `submittedAt` 升序）
**`GET /api/jobs/{id}`** → 200 JobView / 404 未知 id
**`DELETE /api/jobs/{id}`** → 200 `{ "id": "…", "state": "CANCELLED" }` / 404 未知 id / 409 已终态

`JobView`：

```json
{
  "id": "…", "state": "RUNNING", "model": "qwen3.7-max",
  "submittedAt": "2026-10-14T10:00:00", "startedAt": "…", "finishedAt": null,
  "sessionName": "20261014_100001_qwen3.7-max",
  "request": { "maxDays": 365, "maxTurns": 4000, "runs": 1, "seed": 42 },
  "progress": { "days": 37, "maxDays": 365, "runsTotal": 1, "runsDone": 0 },
  "outcomes": null, "error": null
}
```

- `sessionName`：QUEUED 阶段为 `null`（会话目录尚未创建）；RUNNING 后由 `RunComponentFactory` 的 `onSessionCreated` 回调回填。
- `progress.days`：从**已落盘的 `balance/run_<i>_daily_balance.csv` 行数**推导（每模拟日追加一行），完全不侵入 agent。取各 run 的最大值。
- `progress.runsDone`：从 `metrics/run_<i>_analysis.json` 个数推导，复用 `SessionScanner.runIndexes()` 同一套正则口径 `run_(\d+)_analysis\.json`。
- `outcomes`：仅终态填充，每项含 `index`、`canonicalReason`、`finalDay`；FAILED/CANCELLED 时为 `null`。
- `error`：仅 FAILED 填充，形如 `SimpleName + ": " + message`；**不含堆栈、不含任何形似密钥的内容**。

进度推导在 `sessionName` 为 `null` 或目录尚不存在时返回全 0，不抛异常。

**跨站防护（`Origin` 校验）的具体判法**：仅对 `POST`/`DELETE` 生效。取请求的 `Origin` 头：
- 缺失 → 放行（curl / 同源导航 / 非浏览器客户端）
- 存在且等于 `http://127.0.0.1:<port>` 或 `http://localhost:<port>`（`<port>` = `DashboardServer.port()` 的实际绑定值）→ 放行
- 其余 → `ApiException(403, "跨域请求被拒绝")`

比对基准用**服务端已知的绑定端口**，不用请求的 `Host` 头（`Host` 可被 DNS rebinding 篡改）。这是必要防护：服务虽绑 127.0.0.1，但浏览器里任意网页都能向它发 POST，不挡的话任何恶意页面都能拿用户的 API key 跑几小时的任务。

### 4.5 前端

沿用零依赖原生 JS + 浅色主题，`index.html` / `app.js` / `app.css` 各增两块。

**两处必须先改的既有前端事实**（否则新面板在空 `log/` 下永远不可见）：
1. `app.js` 的 `boot()` 在 `state.sessions.length === 0` 时**直接 `return`**，后面的事件绑定与渲染全部不执行。新语义下用户很可能是先开服务、再在页面上跑第一个 run，此时 `log/` 为空——必须重构 `boot()`：把「新建运行」与「任务队列」面板的初始化提到空会话早退之前，空会话只影响会话列表与对比区的文案。
2. 现有 `api(path)` 助手只能 GET（`fetch(path)`），且报错时 `throw new Error(\`${res.status} ${res.statusText}\`)` **丢弃了服务端的 JSON `error` 文本**。提交表单需要展示服务端的具体原因，故新增一个 `apiSend(method, path, body)` 助手：解析响应 JSON，失败时把 `error` 字段放进抛出的 `Error.message`。既有 `api()` 不动。

**「新建运行」面板**
- 模型下拉由 `GET /api/models` 填充；`keyReady=false` 的项 `disabled`，option 文本标注缺哪个变量。
- 主模型就绪但 **NPC 密钥缺失**时，面板顶部显示告警条。
- `maxDays` / `maxTurns` / `runs` / `seed` 四个数字输入，placeholder 即 CLI 同值默认（365 / 4000 / 1 / 42）。
- 提交按钮 + 内联错误区，直接展示服务端 400/409/413 的 `error` 文案。

**「任务队列」面板**
- 表格列：模型 / 状态徽章 / 进度条（`days` ÷ `maxDays`）/ 会话名 / 提交时间 / 取消按钮 / 错误原因。
- 会话名在 RUNNING 后出现，点击 → 滚动到会话列表并选中该会话，衔接既有下钻面板。
- 轮询间隔 2.5 秒，**仅在存在非终态 job 时轮询**；全部终态后停止轮询并触发一次会话列表刷新。
- 取消按钮文案如实标注：「取消（尽力而为，可能需等到下一次模型调用）」。

`app.js` 沿用既有 `api()` 助手与 **generation 守卫**模式（commit `7809bd7` 专门修过陈旧响应竞态；新代码必须同构，否则快速切换会出现旧响应覆盖新状态）。`app.css` 复用既有 `.kpi` / `.table-wrap` / `.hint` 类，新增状态徽章配色；`.dimension` 类曾因缺 `min-width:0` 叠加全局 `white-space:nowrap` 而必然溢出，新增容器需自查同一类问题。

### 4.6 `ServeOptions` mixin 与 `--log-dir` 冲突

`CliRunOptions` 有 `--log-dir`（String，默认 null），`ServeCommand` 也有 `--log-dir`（Path，默认 `log`）。若把 `ServeCommand` 的选项整体 mixin 进根命令，Picocli 会在启动时因重复声明抛异常。

解法：**`ServeOptions` mixin 只含 `--port`（默认 8080）/ `--host`（默认 `127.0.0.1`）/ `--catalog`**，同时 mixin 进 `BenchmarkCommand` 与 `ServeCommand`。`ServeCommand` 保留自己的 `--log-dir`（back-compat）；`BenchmarkCommand` 复用 `CliRunOptions.logDir()`（null → `./log`）。语义上这两个 `--log-dir` 指的本来就是同一个目录（写侧产物根 = 读侧会话根），合并是对的，缺省都解析为 `./log`。

`ServeOptions` 与 `DashboardLauncher` 的职责见 §4.3 新增单元表；两个命令入口都薄封装 `DashboardLauncher`，`awaitShutdown` 的关闭钩子注册/移除逻辑不再有两份。

## 5. 增量 2：默认以 Web 服务启动

### 5.1 入口

`EcommerceBenchApplication` **一字不改**——仍是 `WebApplicationType.NONE`，端口由 `DashboardServer` 绑定而非 Spring 绑定，保留「Spring 不监听端口」这条既有不变量。`application.yml` 不动。

`BenchmarkCommand.call()` 分支：
- `--model` 空白 → `DashboardLauncher`，退出码语义同 `serve`（正常中断 0，目录不存在或端口不可用 2）
- `--model` 有值 → 现有批处理路径**逐字不变**，退出码 0/1/2 不变

**既有测试 `BenchmarkCommandTest.modelIsRequired` 必须重写**：它断言 `exitCode("--max-days", "1")` 非零，而新语义下这条命令会**启动服务并永久阻塞**，测试会挂死。解法：`BenchmarkCommand` 新增构造重载 `(RunComponentFactory, DashboardLauncher)` 作为可注入接缝，无参构造委托为 `(null, new DashboardLauncher())`；`DashboardLauncher` 的 provider/env 构造**延后到 `launch()` 内部**，避免无参构造就触发文件读取与 stderr 噪声。测试传入记录型 stub launcher，断言「被调用 + 退出码 0」。

`serve` 子命令保留，行为与根命令无参数路径完全一致。

### 5.2 错误处理

- **提交时拦截，而非运行时炸**：密钥缺失、模型键未知、数值非正、JSON 畸形一律 400。绝不让 job 起来跑几分钟后才在 LLM 调用处失败。
- 400 响应**不回显原始 body**（避免反射型内容注入）；模型键未知时只报可用键数量，不罗列全部 18 个。
- `RunLauncher` 抛任何 `Throwable` → job 置 FAILED，`error` 只存 `SimpleName + ": " + message`。

### 5.3 降级

- `.env` 缺失 → 正常启动，`keyReady` 全 false，前端提示「未检测到密钥，请启动前 export 或在仓库根放置 `.env`」。
- 进行中的会话目录会被 `SessionScanner` 扫到（它只列目录、忽略 `.` 前缀），此时 `analysis.json` 尚未生成 → 下钻端点 404，前端沿用既有 hint 分支，不新增特殊态。

### 5.4 取消语义（尽力而为）

机制：中断 job 线程 → `RunCoordinator` 阻塞的 `pool.invokeAll()` 抛 `InterruptedException` → JDK `invokeAll` 对未完成任务调 `cancel(true)`，级联中断 run 线程 → 正在阻塞的 `HttpClient.send()`（声明 `throws InterruptedException`）抛出，`RetryExecutor.sleep()` 复位标志并终止退避 → `RunCoordinator` 捕获后重新置位中断标志并抛 `IllegalStateException("并行 run 编排被中断")` → `JobService` 据此把 job 置 CANCELLED。

**不承诺立即停止**：agent 主循环零中断检查，若中断落在两次 LLM 调用之间的纯计算段，要等下一个阻塞点才生效。UI 文案必须如实标注。已产出的部分产物留在磁盘。

### 5.5 取消竞态与服务关闭

- `Job` 的状态迁移在 `synchronized` 内进行，且只允许从非终态迁出。取消与正常完成同时到达时，以先落地的终态为准；已终态的 DELETE → 409。
- 服务关闭时仍有 RUNNING job → `DashboardServer.close()` 中断 job 线程并有限等待，**不承诺优雅收尾**；产物可能只写一半，与直接 kill 掉 CLI 进程的后果一致。

## 6. 测试策略

全程 TDD：先写测试，再写实现。

| 测试 | 锁定的契约 |
|---|---|
| `SecretsFileLoaderTest` | 缺文件 / 空文件 / 畸形行 / 引号包裹 / `#` 注释 / **真实 env 优先**；畸形输入永不抛异常（含 `RuntimeException`） |
| `EffectiveEnvironmentTest` | 合并优先级：env 覆盖 `.env`；`.env` 只填补缺失项 |
| `JobServiceTest`（假 `RunLauncher`，**不碰 LLM**） | QUEUED→RUNNING→SUCCEEDED；launcher 抛异常→FAILED；取消 RUNNING→CANCELLED；取消终态→409；**串行性**（第二个 job 在第一个完成前不开始）；队列上限→拒绝；进度从 CSV 行数推导；`sessionName` 回填 |
| `JobRequestTest` | 用反射比对 `JobRequest` 与 `CliRunOptions` 的 13 个访问器集合与默认值**逐一对齐**——防两处默认值漂移的关键测试 |
| `DashboardServerTest`（扩展，**路由与状态码全部走真实 HTTP**） | ① 既有 `rejectsNonGetRequest` 保持原样且仍绿；② POST 202 / 非法 body 400 / 缺密钥 400 / 未知路径 404 / 对 GET-only 路径发 DELETE→405 / `Origin` 不符→403 / body 超限→413；③ 端到端「POST 一个用假 launcher 的 job → 轮询到 SUCCEEDED」。注：仓库无 `ApiRouterTest`，现有路由测试全部在本文件里走真 HTTP，**沿此惯例不新建路由级测试文件** |
| `BenchmarkCommandTest` | 无 `--model` → 进 serve 分支（`--port 0` + 立即中断，不真阻塞）；有 `--model` → 行为与现状逐字一致 |
| `ModelsEndpointTest` | `keyReady` 与 `ProviderResolver` 判定同源；`npc` 段存在；缺 `OPENAI_API_KEY` 时 NPC 段 `keyReady=false` |
| `RunComponentFactoryTest`（**新建**，仓库无此文件） | `onSessionCreated` 回调在会话目录创建后触发一次；既有 2 参构造行为不变。**测试切点：直接调包级 `sharedRunDirectory(options)`**（只读 `logDir()` 与 `modelKey()`）+ `@TempDir` + 记录型 `Consumer`，避开 `create()` 对 catalog / tokenizer / LLM 的完整依赖。该测试与被测类同包，故包级可见即可访问 |

## 7. 门禁基线与验收

- **主门禁：`mvn -o clean test`** — 7 个模块（domain/simulation/opponent/llm/agent/evaluation/app）BUILD SUCCESS；`18+45+32+10+60+84+105 = 354 tests, 0 failures, 1 skipped` 为起点，只增不减。
- **风格门禁：`mvn -pl ecbench-evaluation,ecbench-app checkstyle:check`** — 0 违规。
- **禁用 `mvn clean verify` 作为门禁**：Spotless 绑在 `verify` 阶段，而根 pom 的 `<googleJavaFormat/>` **未设 `<style>AOSP</style>`**，默认 2 空格与仓库既有 4 空格模块冲突，`spotless:check` 在 evaluation 模块即报 20+ 文件违规。这是**存量红基线，不是本次引入**。
- **禁用 `mvn spotless:apply` 修复**：它会重排整模块既有文件、产生巨量无关 diff。正解（把根 pom 改为 AOSP style）属独立任务，本次不做。
- 根级 `mvn checkstyle:check`（全模块）同样为存量红：opponent 1 处 + llm 4 处。只对所改模块跑 checkstyle。
- `RunComparator.METRIC_DEFS` 仍 29 行（Python 对齐契约不破）。
- 安全码回归：`400 / 404 / 405 / 404` 零回归，新增 `403 / 409 / 413 / 202`。
- 手工冒烟：`curl -sS -D - -o <body文件>` **单条**核对状态码与 `Content-length`（内联 `for` 循环与 `-w "%{http_code}"` 拼接会串码误判）；清理后台进程用 `kill <PID>`，**不用 `pkill -f`**（会匹配执行它的 shell 自身）。验收前确认监听进程的启动时间晚于本次构建（旧进程加载的 jar 被 `mvn clean` 删除后仍在跑，静态资源会开始 404，易误判为「新构建坏了」）。
- 端到端真跑一个 `maxDays=2` 的 job 需用户提供 LLM API key；无 key 时只验 400 拦截路径。

## 8. 编码约定

- `ecbench-app` 4 空格缩进；`ecbench-domain` 2 空格；`resources/web` 前端三件套 2 空格。新增文件镜像所在模块既有风格。
- Checkstyle 实际生效规则：`AvoidStarImport`、`FileTabCharacter`、`LineLength(≤120)`、命名类规则、`MethodLength(≤150)`。**无 `ImportOrder`、无 `UnusedImports`** → 多余导入不会被门禁拦下，需评审自查。（Spotless 配置里虽有 `removeUnusedImports`，但因 §7 的 AOSP 冲突而实际不生效。）
- 含中文的行按**字符数**而非字节数量行宽（`awk`/`wc` 按字节统计会给出约 3 倍虚高值而误判超限）。
- `app` 模块无 JaCoCo 覆盖率硬门（≥85% 只施加于 domain / simulation / opponent），但仍按 TDD 覆盖新逻辑。
- Service 类通过构造器注入 `ObjectMapper` 并提供带默认值的重载构造；JSON 用 `ObjectNode`/`ArrayNode` 手工拼装，可空字段显式写 JSON null 以维持前端形状稳定。
- 非法输入抛 `IllegalArgumentException`（→400）/ `ArtifactNotFoundException`（→404），由路由层统一转状态码，不返回 null 或空集合。

## 9. 文档改动

- README：§启动（默认起 Web）、§serve、参数表更新；新增 §Web 触发运行（含 `.env` 说明、密钥要求、NPC 模型需 `OPENAI_API_KEY` 这一坑、取消的尽力而为语义）。
- `.gitignore`：新增 `.env`。

## 10. 已知遗留与偏差

- **工作树脏**：`ecbench-domain` / `ecbench-simulation` 有 18 个文件未提交（189 插入 / 22 删除，Javadoc `@param` 补全与注释换行的格式化改动）。本次实施**只 `git add` 明确属于本次改动的文件，绝不 `git add -A`**，把它们原样留在工作树。
- 队列上限 8 是拍定的经验值，无实测依据；若真实使用中频繁撞上限再调。
- `ProviderResolver` 缺密钥时的错误信息用 `preset.keyEnvironment()`，对 `openai-compatible` + 自定义 `apiKeyExpression` 的模型会误报成 `OPENAI_API_KEY`（实际缺的是 `DASHSCOPE_API_KEY` 等）。这是既有缺陷，本次**不修**（属 `ecbench-llm` 范围）；`/api/models` 的 `missingEnv` 字段自行从 `${VAR}` 提取以给出准确变量名。
- 旧 spec 记录的 S1 残余项（run 选择器、余额多列、`negotiation_metrics.json` / `negotiation_records.jsonl` / `output.log` 端点）与 S3 导出能力（PNG/CSV/JSON 下载）本次**不做**。
- 阶段 B 的浏览器桌面版布局验收、以及需真数据的学习维度二轮验收仍是未完成项，本次不覆盖。
