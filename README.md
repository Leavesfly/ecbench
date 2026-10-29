# E-CommerceBench · Java 实现

E-CommerceBench 电商经营基准的 **Java 17 + Spring Boot 3 + Maven 多模块**等价重写版本。它以 CLI 批处理形态驱动 LLM Agent，在**确定性**的拟真电商经济环境中进行多轮经营决策（开店、定价、采购谈判、发货、退货、结算），并产出与 Python 参考实现兼容的轨迹 / 余额 / 指标产物，另附一个零依赖的本地 Web 看板用于对比多 run、下钻单 run 与在线提交新运行。

> **独立可构建、可运行**：运行期不依赖 Python，仓库自带 `data/`、`models_config.json`、`tokenizer/tokenizer.json` 全部资源副本，克隆后即可构建运行。

---

## 目录

- [1. 项目简介](#1-项目简介)
- [2. 架构总览](#2-架构总览)
- [3. 环境要求](#3-环境要求)
- [4. 快速开始](#4-快速开始)
- [5. 命令行接口](#5-命令行接口)
- [6. 模型与 Provider](#6-模型与-provider)
- [7. 资源与数据文件](#7-资源与数据文件)
- [8. 核心子系统](#8-核心子系统)
- [9. 运行产物](#9-运行产物)
- [10. 评估与可视化](#10-评估与可视化)
- [11. Web 看板（serve）](#11-web-看板serve)
- [12. 测试与质量门](#12-测试与质量门)
- [13. 项目结构](#13-项目结构)
- [14. 与 Python 参考实现的关系](#14-与-python-参考实现的关系)

---

## 1. 项目简介

E-CommerceBench 用一套仿真电商经济来考察 LLM Agent 的**长期经营决策能力**：Agent 需要在有限资金下开店、选品、与供应商谈判采购、定价销售、发货、处理退货，并在每日结算中存活下来（避免破产）。供应商由**谈判内核**裁决价格与条款，并可能实施 5 类欺诈；LLM 只负责渲染对话文本，不决定成交价，从而保证评测的确定性与可复现性。

### 核心特性

- **确定性仿真经济引擎**：三账户资金体系（银行 / 待结算托管 / 平台钱包）、FIFO 仓储批次、由 9 个处理器组成的日级流水线（运营成本 → 仓储费 → 销售 → 超时取消 → 退货 → 结算 → 配送 → 声誉 → 破产）。同 `seed` 完全可复现。
- **谈判内核 + 5 类供应商欺诈**：VIP 费门控、虚假未来折扣、数量诱饵、次品交付、虚假紧迫感。价格由内核裁决，LLM 仅渲染对话。
- **18 个 Agent 工具**：工具 JSON Schema 与 Python 参考实现逐键对齐。
- **多 LLM Provider 适配**：OpenAI Chat / Responses、Anthropic Messages、OpenAI 兼容端点（Gemini / OpenRouter / Qwen / GLM / Kimi / DeepSeek），统一同步接口 + 指数退避重试。
- **精确 token 计数 + 上下文自动裁剪**：基于 HuggingFace `tokenizer.json`，超阈值时按工具调用组裁剪，并保留运行期备忘录（Memo）。
- **并行多 run 编排**：各 run 组件完全隔离，随机种子按 `seed + index` 派生。
- **评估与可视化**：余额曲线绘图、chatbox 会话提取、跨会话指标比较、跨模型七轴归一化雷达与排名。
- **本地 Web 看板**：零依赖单页应用（纯 SVG 图表），对比多 run、下钻单 run、在线提交运行并轮询进度。
- **全离线 E2E + Python↔Java 双向兼容测试**；核心模块 JaCoCo 行覆盖率门 ≥ 85%。

---

## 2. 架构总览

### 模块职责

工程由一个 Maven 聚合 POM 统一编排 7 个子模块：

| 模块 | 职责 |
|---|---|
| `ecbench-domain` | 值类型（`Money`）、目录 / 配置加载（`CsvCatalogLoader`、`ModelRegistryLoader`）、确定性随机流（`RandomStreams`）、分层异常基类 |
| `ecbench-simulation` | 确定性经济引擎（`SimulationEngine`）、9 个日切处理器、账户与仓储状态、需求模型、事件调度、统计 |
| `ecbench-opponent` | 谈判内核（`KernelManager` / `CounterpartKernel`）、欺诈建模、订单处理、谈判指标聚合 |
| `ecbench-llm` | 多 Provider LLM 客户端、token 计数契约、重试执行器、Provider 解析 |
| `ecbench-agent` | 18 个工具、chatbox 编排、上下文裁剪、运行期记忆、Agent 主循环（`EcommerceBenchAgent`） |
| `ecbench-evaluation` | 日志读取、余额 / 雷达绘图、chatbox 提取、跨会话比较、七轴归一化画像、学习维度指标 |
| `ecbench-app` | CLI 入口、Spring 无 Web 装配、并行编排、兼容日志写入、Web 看板与任务队列 |

**依赖方向**（单向、无环）：

```
app ─▶ agent ─▶ { simulation, opponent, llm } ─▶ domain
evaluation ─▶ domain
```

### 运行时数据流

```
                 ┌───────────────────── ecbench-app（CLI / 并行编排 / 日志 / Web）─────────────────────┐
                 │                                                                                    │
   CLI 参数 ────▶ RunCoordinator ────▶ RunComponentFactory（每 run 装配一整套隔离组件）               │
                 │                              │                                                     │
                 │                              ▼                                                     │
                 │                   EcommerceBenchAgent（主循环）                                     │
   LLM Provider  │        ┌── 请求 / 工具调用 ──┤                                                      │
   客户端 ◀──────┼────────┘                     ▼                                                      │
  (ecbench-llm)  │                  18 个工具 ────▶ SimulationEngine（经济仿真）                       │
                 │                       │                    ▲                                        │
                 │                       ▼                    │                                        │
                 │            ChatboxCoordinator ──▶ 谈判内核 / 欺诈 / 订单（ecbench-opponent）         │
                 │                                                                                     │
                 └── 产物：trajectories / balance / metrics（与 Python 兼容布局）──────────────────────┘
```

### 每 run 的组件隔离

`RunComponentFactory.create(index, options)` 为每个 run 装配一整套组件：

- **只读单例跨 run 共享**（惰性初始化、加锁）：`CatalogData`、`MarketGuidance`、`ModelRegistry`、`TokenCounter`、`RunDirectory`。
- **可变状态每 run 全新建**：`SimulationEngine`、`KernelManager`、`MemoryStore`、`ConversationStore`、主模型与 NPC 两个 `LlmClient`、`ContextEditor`、18 个工具、`ToolManager`、`Agent`、`Observer`——因此并行 run 之间完全隔离。
- **随机种子按 `seed + index` 派生**：保证同 run 可重现，各 run 又互不相同。

> 装配层通过 `ApplicationWiring`（Spring `@Configuration`）只暴露线程安全的共享单例（`ObjectMapper`、`LlmClientProvider`、`RunComponentFactory`）；`LlmClientProvider` 是唯一的 LLM 客户端创建入口，测试可注入假实现，无需真实凭据即可装配完整 run。

---

## 3. 环境要求

| 项 | 要求 |
|---|---|
| JDK | **17**（enforcer 强制 `[17,18)`） |
| Maven | **3.6.3+** |
| 磁盘 / 网络 | 构建需访问 Maven 中央仓库；运行期离线（LLM 调用除外） |

主要依赖版本：Spring Boot `3.5.16`、Picocli `4.7.7`、DJL HuggingFace Tokenizers `0.38.0`、XChart `4.0.4`、Apache Commons CSV `1.14.1`、WireMock Standalone `3.13.1`（测试）、JaCoCo `0.8.15`、Spotless `3.10.2`。

---

## 4. 快速开始

### 构建可执行 fat jar

```bash
mvn -DskipTests clean package
# fat jar -> ecbench-app/target/ecbench-app.jar
```

> 日常构建用 `mvn -DskipTests clean package` 即可；若要跑完整质量门（测试 + Checkstyle + JaCoCo + Spotless），用 `mvn clean verify`，现已可全绿，详见 [§12](#12-测试与质量门)。

### 三种运行形态

可执行 jar 有三种形态，由子命令与是否带 `--model` 决定：

| 形态 | 触发方式 | 说明 |
|---|---|---|
| **Web 看板**（默认） | 不带 `--model`，或显式 `serve` | 启动本地 dashboard 并阻塞，浏览 / 对比 / 提交 run |
| **批处理运行** | 带 `--model <键>` | 跑基准，与 Python `run.py` 兼容，按 `0/1/2` 退出 |
| **评估** | `evaluate <子命令>` | 无 Web 的批处理：绘图 / 提取 / 比较 / 画像 |

```bash
# ① 起 Web 看板（默认行为；等价于显式的 serve 子命令）
java -jar ecbench-app/target/ecbench-app.jar
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080

# ② 跑批处理（先设置对应 Provider 的密钥环境变量）
export GEMINI_API_KEY=...        # 或 OPENAI_API_KEY / ANTHROPIC_API_KEY / DASHSCOPE_API_KEY 等

# 单次运行，限制天数与轮数
java -jar ecbench-app/target/ecbench-app.jar --model gemini-3.5-flash --max-days 10 --max-turns 50

# 5 个并行 run，固定种子（可复现）
java -jar ecbench-app/target/ecbench-app.jar --model gpt-5.6-sol --runs 5 --seed 42

# ③ 评估：跨会话比较
java -jar ecbench-app/target/ecbench-app.jar evaluate compare log/<sessionA> log/<sessionB>
```

应用始终以 Spring Boot `WebApplicationType.NONE` 启动，Spring 自身不监听任何端口；dashboard 的端口由内置的 JDK `HttpServer` 绑定（默认 `127.0.0.1:8080`），且只在走 Web 分支时才绑定。

---

## 5. 命令行接口

### 5.1 根命令（批处理 / 默认 Web）

根命令 `ecbench` 既是有 `--model` 时的批处理运行器，也是无 `--model` 时的 Web 看板入口。`--port` / `--host` / `--catalog` 三个 Web 选项也被根命令接受（两条路径共用同一套启动编排），因此 `java -jar … --port 8081` 与 `java -jar … serve --port 8081` 等价。

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

> `--seed` 与 `--data-dir` 是相对 Python `run.py` 新增的两个可选参数，不破坏原 CLI 兼容性。

### 5.2 退出码

| 路径 | 退出码 |
|---|---|
| 批处理 | `0` 全部 run 成功；`1` 至少一个 run 失败；`2` 配置 / 参数错误 |
| Web（serve / 无 `--model`） | `0` 正常中断（Ctrl-C）；`2` 日志目录不存在或端口不可用 |
| evaluate | `0` 成功；`2` 失败（打印原因，不吞异常） |

### 5.3 `evaluate` 子命令

`evaluate` 是 Python `evaluation/*.py` 脚本的 Java 端口，含四个子命令（详见 [§10](#10-评估与可视化)）：`plot`、`extract-chatbox`、`compare`、`profile`。

### 5.4 `serve` 子命令

`serve` 与根命令无 `--model` 时的行为完全一致，都仅绑定本地回环，用于浏览 / 对比 / 提交 run（详见 [§11](#11-web-看板serve)）。

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--log-dir` | `log` | 会话根目录（每个子目录 = 一个会话） |
| `--port` | `8080` | 监听端口，`0` 表示随机分配 |
| `--host` | `127.0.0.1` | 绑定地址，默认仅本地回环 |
| `--catalog` | `data/suppliers.csv` | 供应商名册 CSV，用于在 chatbox 谈判记录上标注供应商类型与欺诈类型 |

---

## 6. 模型与 Provider

### 6.1 模型注册表 `models_config.json`

模型键见 `models_config.json`，收录论文排行榜的 **18 个模型**，例如：

`gpt-5.6-sol`、`gpt-5.5`（OpenAI，`responses` 风格）、`claude-fable-5`、`claude-opus-4-8/4-7/4-6`（Anthropic）、`gemini-3.5-flash`、`gemini-3.1-pro`（Google）、`qwen3.8-max-preview`、`qwen3.7-max`、`qwen3.6-plus`、`qwen3.5-plus`（DashScope）、`glm-5.2-max`、`glm-5.2-high`、`glm-5.1`（智谱）、`kimi-k3`、`kimi-k2.6`（Moonshot）、`deepseek-v4-pro`（DeepSeek）。

每条模型项可声明：

| 字段 | 说明 |
|---|---|
| `provider` | `openai` / `anthropic` / `google` / `openrouter` / `openai-compatible` |
| `model_name` | Provider 侧真实模型名 |
| `api_style` | `chat`（默认）或 `responses` |
| `effort` | 推理强度 `low` / `medium` / `high` / `xhigh` / `max`，作为 `reasoning_effort` 发送 |
| `base_url` | 仅 openai-compatible 端点需要 |
| `api_key` | 以 `${ENV_VAR}` 形式引用环境变量（文件本身不含密钥，可安全提交） |
| `extra_body` | 厂商私有 body 字段（如某系列的 `thinking` 开关） |
| `thinking_env` | 控制思考模式的环境变量名（如 `CLAUDE_THINKING`） |

### 6.2 供应商 NPC 模型 `npc_tools`

`npc_tools` 是**供应商角色扮演模型**（当前配置为 `qwen3.6-plus`，走 openai-compatible / DashScope），仅负责渲染对话文本；所有价格 / 条款由谈判内核裁决，因此选用小模型即可，也可改指向你已有密钥的任意 Provider。注意其字段用 `model`（而非 `model_name`），且**每个 run 都会额外创建一个 NPC 客户端**，其密钥与主模型独立。

### 6.3 Provider 客户端

`LlmClientFactory` 按已解析的 `api_style` / `provider` 分派到四种协议客户端，优先级为：

```
anthropic（AnthropicClient）
  > responses（OpenAiResponsesClient）
  > openai-compatible / google / openrouter（OpenAiCompatibleClient）
  > 其余回退 openai chat（OpenAiChatClient）
```

调用失败经 `RetryExecutor` + `ProviderException.retryable` 做指数退避重试（仅可重试异常触发，非 `ProviderException` 一律视为不可重试）。

### 6.4 密钥与环境变量

密钥**一律从环境变量读取**（`models_config.json` 内以 `${ENV}` 引用）：

```
OPENAI_API_KEY   ANTHROPIC_API_KEY   GEMINI_API_KEY   OPENROUTER_API_KEY
DASHSCOPE_API_KEY   ZHIPU_API_KEY   MOONSHOT_API_KEY   DEEPSEEK_API_KEY
```

Web 提交运行时，服务启动会读取「真实环境变量 + 仓库根 `.env`」，**真实环境变量优先，`.env` 只填补缺失项**；密钥永不经浏览器传输（详见 [§11.4](#114-在网页上提交运行)）。

### 6.5 配置优先级

```
默认值  <  models_config.json  <  环境变量  <  命令行
```

`ConfigurationMerger` 按此四级合成不可变的 `BenchmarkOptions`（`RunConfig` + `ContextConfig` + 模型配置路径 + effort）。

---

## 7. 资源与数据文件

仓库自带运行期资源副本（无需仓库外任何文件即可运行）：

| 资源 | 内容 | 规模 |
|---|---|---|
| `data/products.csv` | SKU 目录（品类 / 品牌 / 参考价 / 退货率） | 6886 |
| `data/suppliers.csv` | 供应商（人格 / 紧迫感 / 欺诈类型 / 破产阈值） | 576 |
| `data/category_params.csv` | 品类需求参数 | 60 |
| `data/events.csv` | 随机市场事件 | 50 |
| `data/promotions.csv` | 促销活动 | 8 |
| `data/store_types.csv` | 店铺类型（开店费 / 日租 / 允许品类） | 12 |
| `data/store_playbook.json` | 经营手册 / 市场指引 | — |
| `models_config.json` | 模型注册表（18 模型 + `npc_tools`） | — |
| `tokenizer/tokenizer.json` | HuggingFace 分词器 | — |

### 环境变量覆盖

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ECBENCH_MODELS_CONFIG` | `models_config.json` | 模型注册表路径 |
| `MODEL_EFFORT` | 取自模型项 | 覆盖推理强度 |
| `TOKENIZER_PATH` | `tokenizer/` | 分词器路径 |
| `ECBENCH_CONTEXT_TRIGGER` | `120000` | 触发上下文裁剪的 token 阈值 |
| `ECBENCH_CONTEXT_CLEAR_AT_LEAST` | `60000` | 单次裁剪至少释放的 token 数 |
| `ECBENCH_CONTEXT_KEEP_TOOL_USE` | `2` | 裁剪时保留的最近工具调用轮数 |

> 也可放置 `models_config.local.json`（优先于 `models_config.json`，不纳入版本控制）。资源定位支持「仓库根直接运行」与「`java-impl` 隔离副本」两种形态，由 `ResourcePaths` 自动探测回退。

---

## 8. 核心子系统

### 8.1 仿真经济引擎（`ecbench-simulation`）

`SimulationEngine` 是唯一写入口，聚合领域状态、日切处理器流水线、需求模型与事件调度。

**三账户体系**（`Accounts`）：

| 账户 | 作用 |
|---|---|
| **银行 `bank`** | 支付开店费、运营 / 仓储费、采购与运费等一切成本；连续为负触发破产判定 |
| **待结算托管 `pending_settlement`** | 发货后净收入先进托管，结算窗口到期后转入钱包；退货优先冲抵对应托管批次 |
| **平台钱包 `wallet`** | 托管到期资金入账；`withdraw` 把钱包资金转回银行 |

`total_assets = bank + wallet + pending_settlement`（余额 CSV 中以 `total_balance` 为权威列）。

**日级流水线**：`wait_for_next_day` 推进一天，按固定顺序执行 9 个处理器，随后追加当日事件通知：

```
运营成本 → 仓储费 → 销售 → 超时取消 → 退货 → 结算 → 配送 → 声誉 → 破产判定 →（追加当日事件通知）
```

需求由 `DemandModel` 结合品类参数、季节性、价格与声誉计算；`EventScheduler` 从 `events.csv` 调度随机市场事件。

### 8.2 对手方谈判与欺诈（`ecbench-opponent`）

- **谈判内核**（`KernelManager` / `CounterpartKernel`）裁决每一轮报价与条款，输出结构化决策（`NegotiationDecision` / `NegotiationOutcome`），LLM 只把决策渲染成对话。
- **5 类供应商欺诈**（`FraudType`）：

  | 欺诈类型 | wire 名 | 含义 |
  |---|---|---|
  | VIP 费门控 | `vip_fee` | 要求先付 VIP 费才给"好价" |
  | 虚假未来折扣 | `future_discount` | 承诺并不存在的后续折扣 |
  | 数量诱饵 | `qty_bait` | 用大数量诱导下单，实际不划算 |
  | 次品交付 | `quality_downgrade` | 交付质量低于承诺 |
  | 虚假紧迫感 | `fake_urgency` | 制造抢购 / 涨价假象施压 |

- **订单处理**（`OrderProcessor`）执行成交后的采购下单与排期到货（`ScheduledDelivery`）。
- **谈判指标**（`NegotiationTracker`）聚合每次谈判，产出 `negotiation_metrics.json` 与逐谈判记录。

### 8.3 Agent 主循环与 18 个工具（`ecbench-agent`）

`EcommerceBenchAgent` 每轮：**上下文裁剪 → 构建请求 → 模型生成 → 追加 assistant 消息 → 执行工具调用并追加 `role=tool` 结果 → 检查引擎终止（破产 / 跑满天数）与轮数上限**。连续 3 轮无工具调用则终止。

**终止原因**（`TerminationReason`）：`env_completed`（跑满营业日）、`bankrupt`、`max_turns_reached`、`no_tool_calls`、`llm_error`；下游折叠为 `env_completed` / `env_terminated` 两类规范终态。

**18 个工具**（Schema 位于 `ecbench-agent/src/main/resources/tool-schema/`，与实现类一一对应）：

| 分组 | 工具 |
|---|---|
| 店铺经营 | `open_store`、`close_store`、`check_store_status`、`publish_to_store`、`set_prices`、`return_to_warehouse`、`join_promotion` |
| 仓储物流 | `check_warehouse`、`ship_orders` |
| 财务 | `check_balance`、`withdraw` |
| 市场 / 供应商 | `market_search`、`supplier_search`、`list_products`、`trace_return_sources` |
| 谈判 | `chatbox` |
| 记忆 / 时间 | `operate_memory`、`wait_for_next_day` |

> `ToolRegistry` 按名称索引并保留插入顺序，使暴露给模型的工具 Schema 顺序稳定可复现；`EcommerceToolManager` 执行时对参数错误 / 业务规则错误回填可恢复的 JSON 错误，对基础设施级异常中断批次并统一回填失败。

### 8.4 上下文裁剪与运行期记忆（`ecbench-agent`）

- **上下文裁剪**（`ContextEditor` + `HuggingFaceTokenCounter`）：按「assistant 工具调用 + 紧随 tool 响应」分组，从最旧组起清除直到释放量达标，同时保护 system、首条 user 与最近 `keepToolUse` 个工具组，并附 `<system_warning>` 水位告警。
- **运行期记忆**（`MemoryStore` / `InMemoryMemoryStore`）：run-local 备忘录，按标题索引，`operate_memory` 工具提供 add / get / update / delete / list（list 上限 20）。

### 8.5 LLM Provider 适配（`ecbench-llm`）

统一同步对话补全接口 `LlmClient.generate`，封装四种协议的鉴权、请求体差异与重试；`ProviderResolver` 解析出 `ResolvedProvider`（含最终 base_url、api_style、密钥）。异常体系见 `ProviderException`（携带 `provider` / `statusCode` / `retryable`）。

---

## 9. 运行产物

写入 `log/<timestamp>_<model>/`（与 Python 兼容的布局）：

```text
log/{timestamp}_{model}/
├── trajectories/
│   ├── run_{i}_messages.jsonl          # 消息轨迹（OpenAI wire 格式，逐行 JSON）
│   └── run_{i}_output.log              # 人类可读输出日志
├── balance/
│   └── run_{i}_daily_balance.csv       # 每日余额（total_balance 为权威列）
└── metrics/
    ├── run_{i}_negotiation_metrics.json
    ├── run_{i}_analysis.json
    └── run_{i}_negotiation_records.jsonl   # Java 侧附加产物（见下）
```

- **`analysis.json` 含 8 个面板**：`reward`、`profitability`、`negotiation_quality`、`fulfilment_quality`、`return_management`、`fraud_identification`、`supplier_engagement`、`operational_efficiency`。缺失值显式写 JSON `null` 以保持结构稳定。
- **`negotiation_records.jsonl`** 是 **Java 侧附加产物**：逐谈判原始记录，作为论文 §E.6 学习维度指标（AnchorRatio）的重算输入。Python 参考实现不产出该文件，缺失时学习维度显示为「不适用」。

> 全仓**不使用任何通用日志框架**（无 SLF4J / Log4j / java.util.logging）；所有「日志」均为上述结构化数据文件，由 `CompositeRunObserver` 组合的五个写入器落盘。`application.yml` 仅把 Spring root logger 设为 INFO 并关闭 banner。

---

## 10. 评估与可视化

`evaluate` 子命令是 Python `evaluation/*.py` 的 Java 端口，数值口径与 Web 看板完全共用同一套实现（`RunComparator` / `CrossModelProfiler` / `RankingTable`）。

```bash
# ① plot：绘制余额曲线 PNG（纵轴用权威列 total_balance，可叠加多条）
java -jar ecbench-app/target/ecbench-app.jar evaluate plot \
     --input log/<session>/balance/run_0_daily_balance.csv \
     --output log/<session>/plots/balance.png --overlay

# ② extract-chatbox：按供应商提取 chatbox 会话
java -jar ecbench-app/target/ecbench-app.jar evaluate extract-chatbox \
     --input log/<session>/trajectories/run_0_messages.jsonl \
     --output log/<session>/chatbox

# ③ compare：跨会话比较 analysis 指标（可传入多个会话目录）
java -jar ecbench-app/target/ecbench-app.jar evaluate compare log/<sessionA> log/<sessionB>

# ④ profile：跨模型七轴归一化雷达图 + 排名表（可选导出 JSON/CSV）
java -jar ecbench-app/target/ecbench-app.jar evaluate profile log/<sessionA> log/<sessionB> \
     --output log/radar.png --ranking-output log/ranking.json
```

### 七轴画像（论文 §3.7 / Figure 2）

`ProfileAxis` 定义主评分 + 顺时针 6 维，共七轴：

| 轴 | 标签 | 口径 | 越低越好 |
|---|---|---|---|
| `primary` | Primary score | `final_balance` 均值 | — |
| `negotiation` | Negotiation quality | `CSE+` 均值 | — |
| `fraud` | Fraud avoidance | `spend_bad_share` 均值 | ✓ |
| `solvency` | Cash flow & solvency | `peak_drawdown / peak_total_assets` | ✓ |
| `efficiency` | Operational efficiency | `(final−initial) / tool_calls` | — |
| `execution` | Operations execution | `controllable_return_rate` 均值 | ✓ |
| `learning` | Learning (AnchorRatio) | 由 `negotiation_records.jsonl` 重算 | ✓ |

> **学习轴**口径为论文 §E.6 的 **AnchorRatio**（越低越好，`1.0` 表示与自身价格的随机排列无异），由 `negotiation_records.jsonl` 以固定种子置换零分布重算（`B=400`，同产物逐位可复现）；早于该产物的旧会话在此轴上留空。

---

## 11. Web 看板（serve）

不带 `--model` 的根命令与 `serve` 子命令行为完全一致，都仅绑定本地回环（默认 `127.0.0.1`），用于在浏览器里对比多 run、下钻单 run 并直接提交新运行。

```bash
java -jar ecbench-app/target/ecbench-app.jar serve --log-dir ./log --port 8080 --host 127.0.0.1
# 打开 http://127.0.0.1:8080/ ，Ctrl-C 停止
```

`DashboardServer` 使用 JDK 内置 `com.sun.net.httpserver.HttpServer`（不引入 Spring MVC / Tomcat），固定 4 线程池；`/api` 交给 `ApiRouter`，根路径交给 `StaticAssets`（仅白名单 `web/index.html`、`app.js`、`app.css`，其余一律 404）。

**页面能力**：新建运行表单、任务队列、会话勾选、主评分与六维度排名、六维度诊断卡（八类活动带 / 五类骗局支出 / 退货四层）、排名交叉、七轴雷达、资产轨迹叠加、按维度分组的指标矩阵、单 run 下钻（`analysis.json` + chatbox 谈判记录，含欺诈标注）。

**前端技术栈**：零依赖单页应用——手写 CSS3（`:root` 设计令牌 + CSS Grid）、原生 ES2017+ JavaScript、纯 SVG 自绘图表，不引用任何外部 CDN，无 package.json / 构建工具。

### 11.1 只读 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sessions` | 扫描 `log-dir` 下的会话列表（名称 / 模型键 / run 数 / 是否有余额） |
| GET | `/api/compare?sessions=<name>` | 缺省对比全部会话；返回 `sessions` / `radar` / `ranking` 以及论文 §4.3 的 `primaryScore` / `dimensions` / `rankCrossings` / `learning` 四段 |
| GET | `/api/sessions/<name>/{analysis\|balance\|chatbox}?run=<i>` | 单 run 产物下钻 |
| GET | `/api/models` | 全部模型键 + 各自密钥就绪状态 + 独立的 `npc` 段 |
| GET | `/api/jobs` | 任务列表（含进度） |
| GET | `/api/jobs/{id}` | 单个任务详情 |

维度视图口径见 `DimensionAxis`（论文 §3.7），由 `DimensionService` 独立重算。异常映射：非法会话名 / run / 参数 → 400，产物 / 会话 / 任务缺失与未知端点 → 404，已知路径不支持该方法 → 405，其余运行时异常 → 500（单请求失败不影响服务存活）。

### 11.2 导出 API

全部 GET、只读，返回附件下载：

```
/api/export/{balance.png|radar.png|ranking.csv|ranking.json|matrix.csv}?sessions=<name>&sessions=<name>
```

- 缺省 `sessions` 时取前 12 个会话（与 `/api/compare` 同语义）。
- 余额 PNG 画该会话的**全部 run**（与 `evaluate plot --overlay` 同口径），图例名带会话前缀以区分同名 run；指标矩阵 CSV 为长表 `session,metric,mean,std,n`。
- PNG 由服务端用与 CLI 完全相同的 XChart 渲染代码出字节流，**不落盘**；下载文件名为固定 ASCII 常量，不含用户输入（无 `Content-Disposition` 注入面）。

### 11.3 写侧 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/jobs` | 提交运行，返回 `202` 与任务 id；可选 `jobFileContent` 字段上传预构建 job JSONL 的**内容** |
| DELETE | `/api/jobs/{id}` | 取消任务 |

```bash
curl -sS -X POST http://127.0.0.1:8080/api/jobs \
     -H 'Content-Type: application/json' \
     -d '{"model":"qwen3.7-max","maxDays":10,"maxTurns":50}'
```

### 11.4 在网页上提交运行

**可提交字段**：`model`（必填）、`maxTokens`、`maxTurns`、`maxDays`、`initialBalance`、`dailyFee`、`maxTokenCapacity`、`runs`、`seed`；缺省值与 CLI 完全一致。

**路径字段不接受 HTTP 输入**：`logDir` / `dataDir` / `tokenizerPath` 一律取服务进程的启动配置（否则就成了任意路径写入入口）。

**密钥**：服务启动读取「环境变量 + 仓库根 `.env`」，真实环境变量优先，`.env` 只填补缺失项，密钥永不经浏览器传输。`.env` 已加入 `.gitignore`，格式为每行 `KEY=VALUE`（支持 `#` 注释与成对引号）：

```
DASHSCOPE_API_KEY=sk-xxxx
OPENAI_API_KEY=sk-yyyy
```

> **常见坑**：供应商 NPC 模型（`npc_tools`）由**每个 run** 额外创建客户端，其密钥与主模型**独立**。当前配置下 `npc_tools` 走 openai-compatible / DashScope，因此需要 `DASHSCOPE_API_KEY`（若把 `npc_tools` 改回 openai，则需 `OPENAI_API_KEY`）。缺对应密钥时任何运行都会在装配阶段失败；`GET /api/models` 的 `npc` 段与表单上方的告警条会按 `npc_tools` 实际声明的 `${VAR}` 提前指出缺哪个。

**job JSONL 上传**：表单里的「job JSONL（可选）」等价于命令行的 `--job-file`，但上传的是**文件内容**而不是路径——服务端把内容写进自己生成的临时文件，任务结束（含取消与失败）后自动删除。两点注意：

- 请求体上限 **64 KB**（与其它字段共享），前端按 60 KB 预检，超限直接给中文提示且不发请求；服务端超限返回 `413`。
- `RunComponentFactory.resolveJob` **只使用文件第一行**，多 run 的 job 文件可只保留首个 job 再上传。
- 上传内容在**提交期**就预校验，畸形 JSONL 直接返回 `400`（带 1 基行号），而不是等 run 执行期变成任务 `FAILED`。

**调度**：全局单任务串行队列（上限 8 个未结束任务，超出返回 `409`）；一个任务内部仍可用 `runs` 并行多个 run。任务跑在独立的单线程执行器上，**不占用 HTTP 的 4 个工作线程**。进度从已落盘产物推导——`days` 取 `balance/run_<i>_daily_balance.csv` 的数据行数最大值，`runsDone` 取 `metrics/run_<i>_analysis.json` 的个数，因此不侵入 Agent 主循环，也不新增任何产物文件。前端每 2.5 秒轮询，全部任务终态后自动停止。

**取消是尽力而为**：`DELETE` 会中断执行线程，级联打断正在阻塞的模型调用；但 Agent 主循环不检查中断标志，若中断落在两次调用之间的纯计算段，要等下一个阻塞点才生效。已产出的部分产物留在磁盘。已结束任务再取消返回 `409`。

**状态只存内存**：服务重启后任务列表清空；历史 run 的产物仍在 `log/` 下，会话扫描照常可见。

**跨站防护**：`POST` / `DELETE` 校验 `Origin`，只放行缺失（curl）或等于 `http://127.0.0.1:<port>` / `http://localhost:<port>` 的请求，其余返回 `403`。比对基准用服务端已知的绑定端口而非请求的 `Host` 头（后者可被 DNS rebinding 篡改）。服务虽只绑回环，但浏览器里任意网页都能向它发 POST，不挡则任何恶意页面都能拿用户的密钥跑任务。

**状态语义**：`SUCCEEDED` 只表示「编排正常结束」，不代表每个 run 都跑满了营业日。当所有 run 的 `canonicalReason` 都不是 `env_completed`（破产 / 轮数用尽 / LLM 错误等折叠为 `env_terminated`）时，前端会降级显示为琥珀色的「已结束·未跑满」而非绿色的「已完成」。

---

## 12. 测试与质量门

```bash
mvn clean test                                            # 全模块测试
mvn clean verify                                          # 完整质量门：测试 + Checkstyle + JaCoCo + Spotless（现全绿）
mvn -pl ecbench-evaluation,ecbench-app checkstyle:check   # 所改模块风格门（快速增量）
mvn -DskipTests clean package                             # 重打 fat jar
```

- **测试规模**：全 7 模块共 **91 个测试类、513 个测试**（surefire 实测，0 failures / 1 skipped），含离线 E2E（`BenchmarkEndToEndTest`）与 Python↔Java 双向兼容测试（`PythonCompatibilityTest`）。
- **Checkstyle**（行宽 120、禁 star import、方法 ≤ 150 行）：所改模块须 0 违规。
- **JaCoCo** 行覆盖率门：`domain` / `simulation` / `opponent` 三个核心模块均须 ≥ 85%，未达标即 `BUILD FAILURE`。
- **Spotless**：仓库缩进风格混排（`ecbench-domain` 内部同时有 2 空格与 4 空格文件，其余六个模块为 4 空格），而 Google Java Format 只有 GOOGLE(2 空格) / AOSP(4 空格) 两种全局风格，任一单选都会让另一半文件报红。因此根 pom 的 Spotless **刻意不启用** `googleJavaFormat`，只保留与缩进无关的 `removeUnusedImports` / `trimTrailingWhitespace` / `endWithNewline`，使 `mvn clean verify` 可全绿。

> **勿在功能开发中途执行 `mvn spotless:apply`**——会产生覆盖全仓的巨量白空格 diff。将来若要统一格式，应作为独立任务单独提交。

---

## 13. 项目结构

```text
ecbench/
├── pom.xml                     # Maven 聚合 POM + 依赖版本管理 + 质量门插件
├── models_config.json          # 模型注册表（18 模型 + npc_tools）
├── data/                       # 仿真所需 CSV/JSON（products / suppliers / … / store_playbook）
├── tokenizer/tokenizer.json    # HuggingFace 分词器
├── config/checkstyle/          # Checkstyle 规则
├── docs/superpowers/           # 设计规格与实现计划（specs / plans）
├── log/                        # 运行产物输出目录（会话 = {timestamp}_{model}）
├── ecbench-domain/             # 值类型、目录 / 配置加载、确定性随机、异常基类
├── ecbench-simulation/         # 经济引擎、9 日切处理器、账户 / 仓储状态、需求 / 事件
├── ecbench-opponent/           # 谈判内核、欺诈建模、订单处理、谈判指标
├── ecbench-llm/                # 多 Provider 客户端、token 计数契约、重试、Provider 解析
├── ecbench-agent/              # 18 工具、chatbox 编排、上下文裁剪、记忆、Agent 主循环
├── ecbench-evaluation/         # 日志读取、绘图、chatbox 提取、比较、七轴画像、学习指标
└── ecbench-app/                # CLI 入口、Spring 装配、并行编排、日志写入、Web 看板 + 任务队列
    └── src/main/resources/web/ # 零依赖前端（index.html / app.css / app.js）
```

各模块 Java 包根为 `io.github.ecommercebench.<module>`。

---

## 14. 与 Python 参考实现的关系

本项目是 E-CommerceBench（Python）的**等价重写**：CLI 参数、环境变量、`log/` 产物布局与 Python 兼容，并经 `PythonCompatibilityTest` 双向验证（Java 读 Python 写出的产物、Python 评估脚本读 Java 写出的产物）。行为上采用 Java 自有的固定种子确定性（同 `seed` 可复现，但不与 Python 逐帧一致），并使用日级推进模型。Java 侧新增产物 `negotiation_records.jsonl` 用于学习维度指标重算，Python 参考实现不产出该文件。
