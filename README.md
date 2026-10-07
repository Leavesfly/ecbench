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

构建（`verify` 阶段会跑全部质量门并产出可执行 fat jar）：

```bash
mvn clean verify
# fat jar -> ecbench-app/target/ecbench-app.jar
```

运行基准（先设置对应 Provider 的密钥环境变量）：

```bash
export GEMINI_API_KEY=...        # 或 OPENAI_API_KEY / ANTHROPIC_API_KEY 等

# 单次运行，限制天数与轮数
java -jar ecbench-app/target/ecbench-app.jar --model gemini-3.5-flash --max-days 10 --max-turns 50

# 5 个并行 run，固定种子（可复现）
java -jar ecbench-app/target/ecbench-app.jar --model gpt-5.6-sol --runs 5 --seed 42
```

应用以 Spring Boot `WebApplicationType.NONE` 启动，不监听任何端口。

**退出码**：`0` 全部 run 成功；`1` 至少一个 run 失败；`2` 配置 / 参数错误。

## 命令行参数

与 Python `run.py` 兼容，另新增 `--seed` / `--data-dir` 两个可选参数。

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--model` | （必填） | `models_config.json` 中的模型键 |
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

## 测试与质量门

```bash
mvn clean verify
```

- 全 7 模块共 **56 个测试类、200+ 个测试方法**，含离线 E2E（`BenchmarkEndToEndTest`）与 Python↔Java 双向兼容测试（`PythonCompatibilityTest`）。
- **Spotless**（google-java-format）+ **Checkstyle**（行宽 120、禁 star import、方法 ≤ 150 行），违规即 `BUILD FAILURE`。
- **JaCoCo** 行覆盖率门：`domain` / `simulation` / `opponent` 均须 ≥ 85%，未达标即 `BUILD FAILURE`。

## 与 Python 参考实现的关系

本项目是 E-CommerceBench（Python）的**等价重写**：CLI 参数、环境变量、`log/` 产物布局与 Python 兼容，并经 `PythonCompatibilityTest` 双向验证（Java 读 Python 写出的产物、Python 评估脚本读 Java 写出的产物）。行为上采用 Java 自有的固定种子确定性（同 `seed` 可复现，但不与 Python 逐帧一致），并使用日级推进模型。
