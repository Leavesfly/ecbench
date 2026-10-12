# E-CommerceBench `serve` Web Dashboard 设计（路线 A：JDK HttpServer）

- 日期：2026-10-10
- 状态：待评审
- 范围：新增只读 Web dashboard，对 `log/` 下已有 run 产物做**多 run / 多会话对比**演示

## 1. 目标

在不破坏项目"默认无 Web、不监听端口"设计的前提下，新增一个显式子命令 `ecbench serve`，
用浏览器提供一个本地 dashboard，用于**演示**多 run 对比：指标对比表、七轴雷达 + 排名、
余额曲线叠加、单 run 下钻（完整指标面板 + 逐供应商谈判记录）。

## 2. 非目标（YAGNI，明确不做）

- 不在页面上发起新的基准 run（不做任何写操作 / 长任务编排）。
- 不做用户鉴权、多租户、历史记录持久化、数据库。
- 不使用 `spring-boot-starter-web` / Tomcat / MVC（避免改动全局启动类型与既有 `WebEnvironment.NONE` 测试）。
- 不做对外网暴露（仅本地）。
- 服务器端不生成图表 PNG（雷达/曲线改由前端 Chart.js 渲染；既有 `RadarPlotter`/`BalancePlotter` 仅供 CLI 使用，不接入 serve）。

## 3. 架构决策

采用 **路线 A**：JDK 内置 `com.sun.net.httpserver.HttpServer`。

理由：
- 零重量级依赖，保留 headless 设计——只有 `serve` 子命令运行时才绑定端口。
- 后端可完全复用 `ecbench-evaluation` 已有的产物读取器与画像/比较器（`ecbench-app` 已依赖 `ecbench-evaluation`）。
- 实现与测试面小：5 个只读 JSON 接口 + 1 个静态单页。

启动接线：根命令 `ecbench`（`BenchmarkCommand`，`@Command(subcommands = {EvaluationCommand.class})`）
新增 `ServeCommand.class` 到 `subcommands`。`main()` 仍以 `WebApplicationType.NONE` 启动 Spring 容器；
`serve` 执行时在主线程 `HttpServer.start()` 后阻塞（`Thread.join` 或 latch），Ctrl-C 退出。
`run` / `evaluate` 行为零改动。

## 4. CLI 契约

```
ecbench serve [--log-dir PATH] [--port INT] [--host STR]
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--log-dir` | `./log`（相对 `user.dir`） | 会话根目录；每个直接子目录视为一个 session |
| `--port` | `8080` | 监听端口；`0` 表示随机端口（便于测试与端口占用回退） |
| `--host` | `127.0.0.1` | 绑定地址；默认仅本地回环 |

行为：
- 启动后 stdout 打印可访问 URL（例如 `http://127.0.0.1:8080/`）与发现的 session 数。
- 退出码：`0` 正常停止；`2` 配置/参数错误（如 `--log-dir` 不存在）。

## 5. 会话发现规则

- session = `log-dir` 的直接子目录（排除隐藏目录与非目录项）。
- session 名称 = 目录名（形如 `<timestamp>_<modelKey>`）。`modelKey` 由目录名最后一个下划线之后的段推断（推断失败则回退为整个目录名）。
- 每个 session 的 run 集合 = `metrics/run_<i>_analysis.json`（经 `RunLayoutResolver` 兼容 `metrics/` 组织布局与 legacy 平铺）。`run` 索引 i 从文件名解析。
- 无任何 session 时，`/api/sessions` 返回空数组，dashboard 显示空态提示。

## 6. 后端接口（只读 JSON）

通用：响应 `Content-Type: application/json; charset=utf-8`；错误返回 JSON `{ "error": "..." }` 与相应 4xx/5xx。
静态资源（`/`、`/app.js`、`/app.css`）从 classpath（`src/main/resources/web/`）读取，`Content-Type` 分别为 `text/html`、`application/javascript`、`text/css`。

| 端点 | 方法 | 复用 | 返回（结构要点） |
|---|---|---|---|
| `/api/sessions` | GET | 目录扫描 | `[{ "name": str, "modelKey": str, "runs": int, "hasBalance": bool }]`，按名字排序 |
| `/api/compare?sessions=a&sessions=b` | GET | `RunComparator.compare(List<Path>)` + `CrossModelProfiler` + `RankingTable` | `{ "sessions":[{ "name", "runCount", "metrics":[{ "label", "mean", "std", "n", "fallback" }] }], "radar":{ "axes":[label...], "series":[{ "name", "values":[double|null...] }], "median":[...] }, "ranking":[{ "rank", "name", "score" }] }` |
| `/api/sessions/{name}/balance?run=i` | GET | `BalanceCsvReader` + `RunLayoutResolver` | `{ "name", "run", "series":[{ "date", "total_balance", "bank_balance", "platform_wallet", "open_stores", "warehouse_items" }] }` |
| `/api/sessions/{name}/analysis?run=i` | GET | `AnalysisJsonReader`（原样透传） | 该 run 的完整 `analysis.json` 对象 |
| `/api/sessions/{name}/chatbox?run=i` | GET | `ChatboxExtractor`（或 `MessageJsonlReader` + 按供应商分组） | `{ "name", "run", "suppliers":[{ "supplier", "messages":[{ "role", "content" }] }] }` |

说明与约定：
- `radar.values` 中缺失/不可计算轴输出 `null`（对齐 `RankingTable` JSON 把 NaN 写成 null 的既有不变量）。
- `ranking.score` 取 `final_balance` 均值（与 `RankingTable` 主分口径一致）。
- `compare` 未显式给 `sessions` 参数时，默认对比 `log-dir` 下发现的全部 session（上限截断到前 12 个，避免雷达过密）。
- 所有 `{name}` 走安全校验（见 §8）。`run` 必须为非负整数且对应文件存在，否则 404。

## 7. 前端 dashboard

技术：单个 `index.html` + `app.js` + `app.css`；图表用 **Chart.js（CDN）**（radar + line），
表格用原生 DOM。无框架、无构建步骤（纯静态脚本），便于演示与维护。

布局（自上而下）：
1. **会话选择区**：checkbox 列表（来自 `/api/sessions`），"全选/清空"，触发 `/api/compare`。
2. **指标对比表**：行=指标 label，列=所选 session 的 `mean ± std`；`fallback` 文本指标直接显示文本。
3. **雷达 + 排名**：左 Chart.js radar（每 session 一个多边形 + 中位数虚线），右排名表（rank/name/score）。
4. **余额曲线叠加**：Chart.js line，多 session/run 的 `total_balance` 叠加；每个 session 可展开选择具体 run（默认 run_0）。
5. **单 run 下钻**：点击 session/run → 拉 `analysis`（分面板折叠展示）与 `chatbox`（逐供应商对话，按角色着色）。

交互失败（网络/空数据）在对应卡片内显示占位提示，不整页报错。

## 8. 安全与健壮性

- **只读**：仅 GET；无副作用。
- **路径穿越防护**：`{name}` 仅允许 `[A-Za-z0-9._-]` 且不含 `..`；解析出的绝对路径必须仍在 `--log-dir` 规范化根目录之下（`startsWith` 校验），否则 400。`run` 校验为 `^\d+$`。
- **绑定**：默认 `127.0.0.1`，不监听公网。
- **并发**：`HttpServer` 固定小线程池（如 4）；后端读取为无状态、可并行。
- **异常**：单请求异常返回 500 + JSON error，记录 stderr，不影响 server 存活。

## 9. 测试计划

- **单元**（`ServeCommand` 的纯逻辑，无需起端口）：
  - session 发现：给定合成 `log/` fixture（2 个 session、各 1–2 个 run 的 `metrics/run_*_analysis.json` + `balance/` + `trajectories/`），断言发现的名称/`modelKey`/run 数。
  - 路径校验：`../`、含 `/`、非法字符 → 拒绝；合法名 → 通过。
- **集成**（临时端口 `port=0` 起 server，用 JDK `HttpClient` 打接口）：
  - `GET /api/sessions` 返回预期 JSON。
  - `GET /api/compare`：结构与所选 session 数一致；`radar.series` 数=会话数；缺失轴为 `null`。
  - `GET /api/sessions/{name}/analysis?run=0`：透传字段（如 `reward.termination_reason`）正确。
  - `GET /api/sessions/{name}/balance?run=0`：`series` 行数与 CSV 一致；含 `total_balance`。
  - 非法 `{name}` → 400；不存在 run → 404。
- 复用 `ecbench-app` 现有测试资源或临时目录（`@TempDir`）构造 fixture；不依赖网络/真实 LLM。
- 前端静态脚本以人工浏览器验证为主（演示用途），不写 JS 单测。

## 10. 质量门与代码规范

- 每次改码后先 `mvn -q spotless:apply` 再跑目标测试（Google Java Format，AOSP 风格以仓库既有配置为准）。
- Checkstyle：行宽 ≤ 120、禁 star import、单方法 ≤ 150 行；新增类补中文 Javadoc，风格对齐现有 `ecbench-app` 类。
- `mvn clean verify`（不带 skip）须 8 模块 BUILD SUCCESS、0 Checkstyle violations；app 模块无 JaCoCo 行覆盖率硬门。

## 11. 文件级改动清单（预估）

新增（均在 `ecbench-app`）：
- `src/main/java/io/github/ecommercebench/app/cli/ServeCommand.java`（picocli 子命令，负责解析参数、装配 handler、启动/停止 server；保持在 ≤150 行的方法切分）。
- `src/main/java/io/github/ecommercebench/app/web/`（小类分职责，便于测试与隔离）：
  - `DashboardServer.java`（封装 `HttpServer` 生命周期、线程池、路由分发）。
  - `SessionScanner.java`（`log-dir` 会话/ run 发现与名字安全校验）。
  - `JsonApiHandler.java`（各 `/api/*` 端点，内部按端点拆方法；复用 evaluation 读取器/画像器）。
  - `StaticHandler.java`（从 classpath 提供 `web/` 静态资源）。
- `src/main/resources/web/index.html`、`app.js`、`app.css`（dashboard 单页）。
- 测试：`ServeCommandTest.java`、`SessionScannerTest.java`、`JsonApiHandlerITCase.java`（或同类命名），fixture 构造工具。

改动（很小）：
- `BenchmarkCommand.java`：`subcommands = {EvaluationCommand.class, ServeCommand.class}`。

不改：`EcommerceBenchApplication.java`（仍 `WebApplicationType.NONE`）、`run`/`evaluate` 行为、`ecbench-evaluation` 既有类（仅被复用）。

## 12. 风险与备注

- Chart.js 走 CDN：演示机需联网（本项目 LLM 调用本就需联网，可接受）。若需完全离线，后续把 `chart.umd.min.js` 放 `web/vendor/` 本地引用（非本次范围）。
- 雷达轴口径完全沿用 evaluation 模块 `ProfileAxis` 的既有实现与已知偏差（学习轴用 `se_half_lift` 代理），serve 不重新定义指标。
- 与"无 Web"设计的关系：以"显式子命令 opt-in 才起端口"的方式满足演示诉求，默认批处理形态与产物兼容性不受影响。

## 13. 验收标准（Definition of Done）

1. `mvn clean verify` 全绿（0 Checkstyle 违规），新增测试通过。
2. `java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080` 可启动，浏览器打开 `http://127.0.0.1:8080/` 能看到四个视图且数据来自真实/fixture 产物。
3. 非法/越界路径被拒（400），默认 run 行为与产物布局不变。
4. 代码符合项目风格（中文 Javadoc、Spotless/Checkstyle 通过）。
