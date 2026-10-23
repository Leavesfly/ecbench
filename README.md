# E-CommerceBench · Java 实现

E-CommerceBench 电商经营基准的 **Java 17 + Spring Boot 3 + Maven 多模块** 等价重写版本：以 CLI 批处理形态驱动 LLM Agent，在拟真电商经济环境中进行多轮经营决策（开店、定价、采购谈判、发货、退货、结算），并产出与 Python 参考实现兼容的轨迹 / 余额 / 指标产物。

> 本项目是**独立可构建、可运行**的 Java 工程，运行期不依赖 Python；仓库自带 `data/`、`models_config.json`、`tokenizer/tokenizer.json` 全部资源副本。

## 目录

- [核心特性](#核心特性)
- [架构总览](#架构总览)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [命令行参数](#命令行参数)
- [模型与 Provider](#模型与-provider)
- [资源与配置](#资源与配置)
- [仿真经济模型](#仿真经济模型)
- [Agent 与 18 个工具](#agent-与-18-个工具)
- [运行产物](#运行产物)
- [评估子命令](#评估子命令)
- [测试与质量门](#测试与质量门)
- [与 Python 参考实现的关系](#与-python-参考实现的关系)

## 核心特性

- **确定性仿真经济引擎**：三账户体系（银行 / 待结算托管 / 平台钱包）、FIFO 仓储批次、由 9 个处理器组成的日级流水线（销售 / 发货 / 退货 / 结算 / 配送 / 声誉 / 破产 / 事件）。同 `seed` 可完全复现。
- **谈判内核 + 5 类供应商欺诈**建模：VIP 费门控、虚假未来折扣、数量诱饵、次品交付、虚假紧迫感；供应商价格由内核裁决，LLM 仅渲染对话。
- **18 个 Agent 工具**，工具 Schema 与 Python 参考实现逐键对齐。
- **多 LLM Provider 适配**：OpenAI Chat / Responses、Anthropic Messages、OpenAI 兼容端点（Gemini / OpenRouter / Qwen / GLM / Kimi / DeepSeek）。
- **精确 token 计数**（HuggingFace `tokenizer.json`）+ 上下文自动裁剪与运行期记忆。
- **并行多 run 编排**：各 run 组件完全隔离，随机种子按 `seed + index` 派生。
- **评估子命令**：余额曲线绘图、chatbox 会话提取、跨会话指标比较。
- **全离线 E2E 测试** + Python↔Java 双向兼容测试；核心模块 JaCoCo 行覆盖率门 ≥ 85%。

## 架构总览

| 模块 | 职责 |
|---|---|
| `ecbench-domain` | 值类型（`Money` 等）、目录 / 配置加载、确定性随机流 |
| `ecbench-simulation` | 确定性经济引擎、日切处理器、账户与仓储状态、统计 |
| `ecbench-opponent` | 谈判内核、欺诈建模、订单处理、谈判指标 |
| `ecbench-llm` | LLM Provider 客户端、token 计数、上下文裁剪 |
| `ecbench-agent` | 18 个工具、chatbox 编排、Agent 主循环 |
| `ecbench-evaluation` | 日志读取、余额绘图、chatbox 提取、多会话比较 |
| `ecbench-app` | CLI 入口、Spring 无 Web 装配、并行编排、兼容日志写入 |

**依赖方向**：`app → agent → {simulation, opponent, llm} → domain`；`evaluation` 仅依赖 `domain`。

```
              ┌──────────────────────── ecbench-app（CLI / 并行编排 / 日志）────────────────────────┐
              │                                                                                     │
   CLI 参数 ─▶ RunCoordinator ─▶ RunComponentFactory（每 run 装配一套隔离组件）                     │
              │                                                                                     │
              ▼                                    EcommerceBenchAgent（主循环）                     │
   LLM Provider 客户端 ◀── 请求 / 工具调用 ──▶  18 个工具 ──▶ SimulationEngine（经济仿真）           │
   (ecbench-llm)                                     │                    ▲                          │
                                                     ▼                    │                          │
                                          ChatboxCoordinator ──▶ 谈判内核 / 欺诈 / 订单（opponent）  │
              └─────────────────────────────────────────────────────────────────────────────────┘
                     产物：trajectories / balance / metrics（与 Python 兼容布局）
```

## 环境要求

- **JDK 17**（enforcer 强制 `[17,18)`）
- **Maven 3.6.3+**

## 快速开始

构建可执行 fat jar：

```bash
mvn -DskipTests clean package
# fat jar -> ecbench-app/target/ecbench-app.jar
```

> 不要在这里用 `mvn clean verify`：它绑定的 Spotless 因根 pom 缺 `<style>AOSP</style>` 而必然报红，详见[测试与质量门](#测试与质量门)。

**默认启动形态是 Web 服务**：不带 `--model` 时进程会启动本地 dashboard 并阻塞；带 `--model` 时行为与从前逐字一致，跑批处理并按 `0/1/2` 退出。

```bash
# 起 Web 看板（默认行为；等价于显式的 serve 子命令）
java -jar ecbench-app/target/ecbench-app.jar
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080

# 跑批处理（先设置对应 Provider 的密钥环境变量）
export GEMINI_API_KEY=...        # 或 OPENAI_API_KEY / ANTHROPIC_API_KEY 等

# 单次运行，限制天数与轮数
java -jar ecbench-app/target/ecbench-app.jar --model gemini-3.5-flash --max-days 10 --max-turns 50

# 5 个并行 run，固定种子（可复现）
java -jar ecbench-app/target/ecbench-app.jar --model gpt-5.6-sol --runs 5 --seed 42
```

应用始终以 Spring Boot `WebApplicationType.NONE` 启动，Spring 自身不监听任何端口；dashboard 的端口由内置的 JDK
`HttpServer` 绑定（默认 `127.0.0.1:8080`），且只在走 Web 分支时才绑定。

**退出码**：批处理路径 `0` 全部 run 成功；`1` 至少一个 run 失败；`2` 配置 / 参数错误。Web 路径正常中断（Ctrl-C）返回 `0`，
日志目录不存在或端口不可用返回 `2`。

## 命令行参数

与 Python `run.py` 兼容，另新增 `--seed` / `--data-dir` 两个可选参数。

`--port` / `--host` / `--catalog` 三个 Web 选项也被根命令接受（两条路径共用同一套启动编排），因此
`java -jar … --port 8081` 与 `java -jar … serve --port 8081` 等价。

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--model` | （无） | `models_config.json` 中的模型键；**缺省时不跑批处理，而是启动 Web dashboard** |
| `--max-tokens` | `16384` | 单次 LLM 调用的最大 token 数 |
| `--max-turns` | `4000` | 单次 episode 的最大 Agent 轮数 |
| `--max-days` | `365` | 最大模拟天数 |
| `--initial-balance` | `100000.0` | 初始银行余额 |
| `--daily-fee` | `50.0` | 每日店铺运营费 |
| `--max-token-capacity` | `128000` | 上下文窗口 token 容量 |
| `--tokenizer-path` | `tokenizer/tokenizer.json` | HuggingFace tokenizer 路径 |
| `--log-dir` | `<当前工作目录>/log` | 日志输出目录 |
| `--job-file` | （可选） | 预构建的 job JSONL 文件 |
| `--runs` | `1` | 并行运行次数 |
| `--seed` | `42` | 确定性随机种子（同 seed 可复现） |
| `--data-dir` | `data/` | 数据目录覆盖 |

## 模型与 Provider

模型键见 `models_config.json`，收录论文排行榜的 18 个模型（如 `gpt-5.6-sol`、`gpt-5.5`、`claude-opus-4-8`、`claude-fable-5`、`gemini-3.5-flash`、`gemini-3.1-pro`、`qwen3.7-max`、`glm-5.2-max`、`kimi-k3`、`deepseek-v4-pro` 等）。其中：

- `npc_tools` 是**供应商角色扮演模型**（默认 `gpt-4o-mini`），仅负责渲染对话文本；所有价格 / 条款由谈判内核裁决，因此选用小模型即可。
- 每条模型项可声明 `provider`、`model_name`、`api_style`（`chat` / `responses`）、`effort`（`low`…`max`，作为 `reasoning_effort` 发送）、`base_url`、`extra_body`、`thinking_env` 等字段。

**Provider 客户端**（`LlmClientFactory` 按 `api_style` / `provider` 分派）：OpenAI Chat、OpenAI Responses、Anthropic Messages、OpenAI 兼容端点。

密钥**一律从环境变量读取**（`models_config.json` 内以 `${ENV}` 形式引用，因此该文件可安全提交）：

```
OPENAI_API_KEY   ANTHROPIC_API_KEY   GEMINI_API_KEY   OPENROUTER_API_KEY
DASHSCOPE_API_KEY   ZHIPU_API_KEY   MOONSHOT_API_KEY   DEEPSEEK_API_KEY
```

## 资源与配置

仓库自带运行期资源副本（无需仓库外任何文件即可运行）：

| 资源 | 内容 |
|---|---|
| `data/products.csv` | 6886 个 SKU（品类 / 品牌 / 参考价 / 退货率） |
| `data/suppliers.csv` | 576 家供应商（人格 / 紧迫感 / 欺诈类型 / 破产阈值） |
| `data/category_params.csv` | 60 个品类需求参数 |
| `data/events.csv` | 50 个随机事件 |
| `data/promotions.csv` | 8 个促销活动 |
| `data/store_types.csv` | 12 种店铺类型（开店费 / 日租 / 允许品类） |
| `data/store_playbook.json` | 经营手册 / 市场指引 |
| `models_config.json` | 模型注册表 |
| `tokenizer/tokenizer.json` | HuggingFace 分词器 |

**配置优先级**：`默认值 < models_config < 环境变量 < 命令行`。

可用环境变量覆盖：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ECBENCH_MODELS_CONFIG` | `models_config.json` | 模型注册表路径 |
| `MODEL_EFFORT` | 取自模型项 | 覆盖推理强度 |
| `TOKENIZER_PATH` | `tokenizer/` | 分词器路径 |
| `ECBENCH_CONTEXT_TRIGGER` | `120000` | 触发上下文裁剪的 token 阈值 |
| `ECBENCH_CONTEXT_CLEAR_AT_LEAST` | `60000` | 单次裁剪至少释放的 token 数 |
| `ECBENCH_CONTEXT_KEEP_TOOL_USE` | `2` | 裁剪时保留的最近工具调用轮数 |

也可放置 `models_config.local.json`（优先于 `models_config.json`，不纳入版本控制）。

## 仿真经济模型

**三账户体系**（`Accounts`）：

- **银行账户 `bank`**：支付开店费、运营 / 仓储费、采购与运费等一切成本；连续为负触发破产判定。
- **待结算托管 `pending_settlement`**：发货后净收入先进托管，经过结算窗口到期后转入钱包；退货优先冲抵对应托管批次。
- **平台钱包 `wallet`**：托管到期资金入账；`withdraw` 把钱包资金转回银行。
- `total_assets = bank + wallet + pending_settlement`（余额 CSV 中以 `total_balance` 为权威列）。

**日级流水线**（`wait_for_next_day` 推进一天，按序执行 9 个处理器）：

```
运营费 → 仓储费 → 销售 → 超时取消 → 退货 → 结算 → 配送 → 声誉 → 破产判定 →（追加当日事件通知）
```

**5 类供应商欺诈**（`FraudType`）：`vip_fee`（VIP 费门控）、`future_discount`（虚假未来折扣）、`qty_bait`（数量诱饵）、`quality_downgrade`（次品交付）、`fake_urgency`（虚假紧迫感）。

## Agent 与 18 个工具

`EcommerceBenchAgent` 主循环每轮：上下文裁剪 → 构建请求 → 模型生成 → 追加 assistant 消息 → 执行工具调用并追加 `role=tool` 结果 → 检查引擎终止（破产 / 跑满天数）与轮数上限。连续 3 轮无工具调用则终止。

**终止原因**（`TerminationReason`）：`env_completed`（跑满营业日）、`bankrupt`、`max_turns_reached`、`no_tool_calls`、`llm_error`；下游折叠为 `env_completed` / `env_terminated` 两类规范终态。

18 个工具（Schema 位于 `ecbench-agent/src/main/resources/tool-schema/`）：

| 分组 | 工具 |
|---|---|
| 店铺经营 | `open_store`、`close_store`、`check_store_status`、`publish_to_store`、`set_prices`、`return_to_warehouse`、`join_promotion` |
| 仓储物流 | `check_warehouse`、`ship_orders` |
| 财务 | `check_balance`、`withdraw` |
| 市场 / 供应商 | `market_search`、`supplier_search`、`list_products`、`trace_return_sources` |
| 谈判 | `chatbox` |
| 记忆 / 时间 | `operate_memory`、`wait_for_next_day` |

## 运行产物

写入 `log/<timestamp>_<model>/`（与 Python 兼容的布局）：

- `trajectories/run_<i>_messages.jsonl`、`run_<i>_output.log`
- `balance/run_<i>_daily_balance.csv`（`total_balance` 为权威列）
- `metrics/run_<i>_negotiation_metrics.json`、`run_<i>_analysis.json`
- `metrics/run_<i>_negotiation_records.jsonl`（**Java 侧附加产物**：逐谈判原始记录，论文 §E.6 学习维度指标的重算输入；Python 参考实现不产出该文件，缺失时学习维度显示为不适用）

其中 `analysis.json` 含 7 个面板：`reward`、`profitability`、`negotiation_quality`、`fraud_identification`、`supplier_engagement`、`return_management`、`fulfilment_quality`。

## 评估子命令

`evaluation/*.py` 三个脚本的 Java 端口：

```bash
# 绘制余额曲线 PNG（纵轴用权威列 total_balance，可叠加多条）
java -jar ecbench-app/target/ecbench-app.jar evaluate plot \
     --input log/<session>/balance/run_0_daily_balance.csv \
     --output log/<session>/plots/balance.png --overlay

# 按供应商提取 chatbox 会话
java -jar ecbench-app/target/ecbench-app.jar evaluate extract-chatbox \
     --input log/<session>/trajectories/run_0_messages.jsonl \
     --output log/<session>/chatbox

# 跨会话比较 analysis 指标（可传入多个会话目录）
java -jar ecbench-app/target/ecbench-app.jar evaluate compare log/<sessionA> log/<sessionB>
```

`evaluate profile` 的七轴画像中，学习轴口径为论文 §E.6 的 **AnchorRatio**（越低越好，1.0 表示与自身价格的随机排列无异），
由 `metrics/run_<i>_negotiation_records.jsonl` 重算得出（固定种子置换零分布，B=400，同产物逐位可复现）；
早于该产物的旧会话在此轴上留空。

### 本地 Web 看板（serve）

不带 `--model` 的根命令与 `serve` 子命令行为完全一致，都仅绑定本地回环（默认 `127.0.0.1`），
用于在浏览器里对比多 run 并直接提交新的运行；`evaluate` 仍以无 Web 的批处理形态运行。

```bash
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080 --host 127.0.0.1
# 打开 http://127.0.0.1:8080/ ：新建运行表单、任务队列、会话勾选、主评分与六维度排名、
# 六维度诊断卡（八类活动带 / 五类骗局支出 / 退货四层）、排名交叉、七轴雷达、资产轨迹叠加、
# 按维度分组的指标矩阵、单 run 下钻（analysis.json + chatbox 谈判记录，含欺诈标注）
# Ctrl-C 停止
```

选项：`--log-dir`（会话根目录，默认 `log`）、`--port`（默认 `8080`，`0` 为随机端口）、`--host`（默认 `127.0.0.1`）、
`--catalog`（供应商名册 CSV，默认取资源根 `data/suppliers.csv`；用于在 chatbox 谈判记录上标注供应商类型与欺诈类型，
名册缺失时自动降级为无标注，服务照常启动）。后三个选项同样可直传给根命令。

只读接口：`/api/sessions`、`/api/compare?sessions=<name>`（缺省对比全部会话；返回 `sessions`/`radar`/`ranking`
以及论文 §4.3 的 `primaryScore`/`dimensions`/`rankCrossings`/`learning` 四段）、
`/api/sessions/<name>/{analysis|balance|chatbox}?run=<i>`、`/api/models`、`/api/jobs`。
数值口径完全复用 `evaluate` 子命令的同一套实现（`RunComparator` / `CrossModelProfiler` / `RankingTable`），
维度视图口径见 `DimensionAxis`（论文 §3.7）；前端为零依赖单页应用，图表以纯 SVG 渲染，不引用任何外部 CDN 资源。

导出接口（全部 GET、只读，返回附件下载）：
`/api/export/{balance.png|radar.png|ranking.csv|ranking.json|matrix.csv}?sessions=<name>&sessions=<name>`，
缺省 `sessions` 时取前 12 个会话（与 `/api/compare` 同语义）。余额 PNG 画该会话的**全部 run**
（与 `evaluate plot --overlay` 同口径），图例名带会话前缀以区分同名 run；指标矩阵 CSV 为长表
`session,metric,mean,std,n`。PNG 由服务端用与 CLI 完全相同的 XChart 渲染代码出字节流，不落盘。

### 在网页上提交运行

看板除了浏览与对比，还能直接提交新的运行。

**密钥**：服务启动时读取「环境变量 + 仓库根 `.env`」，**真实环境变量优先，`.env` 只填补缺失项**。
密钥永不经浏览器传输。`.env` 已加入 `.gitignore`，格式为每行 `KEY=VALUE`（支持 `#` 注释与成对引号）：

```
DASHSCOPE_API_KEY=sk-xxxx
OPENAI_API_KEY=sk-yyyy
```

> **坑**：供应商 NPC 模型（`npc_tools`，默认 `gpt-4o-mini`）走 openai，**每个 run 都需要 `OPENAI_API_KEY`**，
> 即便主模型选的是 qwen / glm / kimi。缺它时任何运行都会在装配阶段失败；`GET /api/models` 的 `npc` 段与
> 表单上方的告警条会提前指出。

**接口**：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/models` | 全部模型键 + 各自的密钥就绪状态 + 独立的 `npc` 段 |
| POST | `/api/jobs` | 提交运行，返回 `202` 与任务 id；可选 `jobFileContent` 字段上传预构建 job JSONL 的**内容** |
| GET | `/api/jobs` | 任务列表（含进度） |
| GET | `/api/jobs/{id}` | 单个任务详情 |
| DELETE | `/api/jobs/{id}` | 取消任务 |

可提交字段：`model`（必填）、`maxTokens`、`maxTurns`、`maxDays`、`initialBalance`、`dailyFee`、
`maxTokenCapacity`、`runs`、`seed`；缺省值与 CLI 完全一致。
**`logDir` / `dataDir` / `tokenizerPath` 不接受 HTTP 输入**，一律取服务进程的启动配置（否则就成了任意路径写入入口）。

表单里的「job JSONL（可选）」等价于命令行的 `--job-file`，但上传的是**文件内容**而不是路径——服务端把内容
写进自己生成的临时文件，任务结束（含取消与失败）后自动删除，因此 `jobFile` 同样不接受路径输入。两点注意：

- 请求体上限 64 KB（与其它字段共享），前端按 60 KB 预检，超限直接给出中文提示且不发请求；
- `RunComponentFactory.resolveJob` **只使用文件第一行**，多 run 的 job 文件可只保留首个 job 再上传。

上传内容在**提交期**就会预校验，畸形 JSONL 直接返回 `400`（带 1 基行号），而不是等 run 执行期变成任务 `FAILED`。

```bash
curl -sS -X POST http://127.0.0.1:8080/api/jobs \
     -H 'Content-Type: application/json' \
     -d '{"model":"qwen3.7-max","maxDays":10,"maxTurns":50}'
```

**调度**：全局单任务串行队列（上限 8 个未结束任务，超出返回 `409`）；一个任务内部仍可用 `runs` 并行多个 run。
任务跑在独立的单线程执行器上，**不占用 HTTP 的 4 个工作线程**。进度从已落盘产物推导——`days` 取
`balance/run_<i>_daily_balance.csv` 的数据行数最大值，`runsDone` 取 `metrics/run_<i>_analysis.json` 的个数，
因此不侵入 Agent 主循环，也不新增任何产物文件。前端每 2.5 秒轮询，全部任务终态后自动停止。

**取消是尽力而为**：`DELETE` 会中断执行线程，级联打断正在阻塞的模型调用；但 Agent 主循环不检查中断标志，
若中断落在两次调用之间的纯计算段，要等下一个阻塞点才生效。已产出的部分产物留在磁盘。

**状态只存内存**：服务重启后任务列表清空；历史 run 的产物仍在 `log/` 下，会话扫描照常可见。

**跨站防护**：`POST` / `DELETE` 校验 `Origin`，只放行缺失（curl）或等于 `http://127.0.0.1:<port>` /
`http://localhost:<port>` 的请求，其余返回 `403`。比对基准用服务端已知的绑定端口而非请求的 `Host` 头（后者可被
DNS rebinding 篡改）。服务虽只绑回环，但浏览器里任意网页都能向它发 POST，不挡则任何恶意页面都能拿用户的密钥跑任务。

**状态语义**：`SUCCEEDED` 只表示「编排正常结束」，不代表每个 run 都跑满了营业日。当所有 run 的 `canonicalReason`
都不是 `env_completed`（破产 / 轮数用尽 / LLM 错误等折叠为 `env_terminated`）时，前端会降级显示为琥珀色的
「已结束·未跑满」而非绿色的「已完成」。

## 测试与质量门

```bash
mvn clean test                                            # 全模块测试
mvn -pl ecbench-evaluation,ecbench-app checkstyle:check   # 所改模块风格门
mvn -DskipTests clean package                             # 重打 fat jar
```

- 全 7 模块共 **89 个测试类、476 个测试**（surefire 实测，0 failures / 1 skipped），含离线 E2E（`BenchmarkEndToEndTest`）与 Python↔Java 双向兼容测试（`PythonCompatibilityTest`）。
- **Checkstyle**（行宽 120、禁 star import、方法 ≤ 150 行）：所改模块须 0 违规。
- **JaCoCo** 行覆盖率门：`domain` / `simulation` / `opponent` 均须 ≥ 85%，未达标即 `BUILD FAILURE`。
- **Spotless 已知红基线**：根 pom 的 `<googleJavaFormat/>` 未设 `<style>AOSP</style>`，默认 2 空格与仓库既有 4 空格模块冲突，
  `spotless:check` 在 `evaluation` 模块即报 20+ 个文件违规，因而 `mvn clean verify` 无法全绿。**不要**用 `mvn spotless:apply` “修复”——
  它会重排整模块既有文件、产生巨量无关 diff。修法是把根 pom 改为 `<googleJavaFormat><style>AOSP</style></googleJavaFormat>`，属独立任务。

## 与 Python 参考实现的关系

本项目是 E-CommerceBench（Python）的**等价重写**：CLI 参数、环境变量、`log/` 产物布局与 Python 兼容，并经 `PythonCompatibilityTest` 双向验证（Java 读 Python 写出的产物、Python 评估脚本读 Java 写出的产物）。行为上采用 Java 自有的固定种子确定性（同 `seed` 可复现，但不与 Python 逐帧一致），并使用日级推进模型。
