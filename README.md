# E-CommerceBench · Java 实现

E-CommerceBench 电商经营基准的 **Java 17 + Spring Boot 3 + Maven 多模块** 等价重写版本：以 CLI 批处理形态，驱动 LLM Agent 在拟真电商经济环境中进行多轮经营决策，并产出与 Python 参考实现兼容的轨迹 / 余额 / 指标产物。

> 本项目是**独立可构建、可运行**的 Java 工程，运行期不依赖 Python；仓库自带 `data/`、`models_config.json`、`tokenizer/tokenizer.json` 资源副本。

## 特性

- **确定性仿真经济引擎**：三账户（银行 / 平台钱包 / 待结算托管）、FIFO 仓储、日级流水线（销售 / 发货 / 退货 / 结算 / 配送 / 声誉 / 破产 / 事件）。
- **谈判内核 + 5 类供应商欺诈**建模；VIP 费门控、数量诱饵、次品交付等。
- **18 个 Agent 工具**，工具 Schema 与 Python 参考实现逐键对齐。
- **多 LLM Provider 适配**：OpenAI Chat / Responses、Anthropic、OpenAI 兼容端点。
- **精确 token 计数**（HuggingFace `tokenizer.json`）+ 上下文编辑与运行期记忆。
- **并行多 run 编排**、兼容日志 / 指标写入、评估子命令（绘图 / 提取 / 比较）。
- **全离线 E2E 测试** + Python↔Java 双向兼容测试；JaCoCo 行覆盖率门 ≥ 85%。

## 环境要求

- JDK 17
- Maven 3.6.3+

## 项目结构（Maven reactor）

| 模块 | 职责 |
|---|---|
| `ecbench-domain` | 值类型（`Money` 等）、目录 / 配置加载、确定性随机流 |
| `ecbench-simulation` | 确定性经济引擎、日切处理器、状态与统计 |
| `ecbench-opponent` | 谈判内核、欺诈、订单处理、谈判指标 |
| `ecbench-llm` | LLM Provider 客户端、token 计数、上下文编辑 |
| `ecbench-agent` | 18 个工具、chatbox 编排、Agent 主循环 |
| `ecbench-evaluation` | 日志读取、余额绘图、chatbox 提取、多会话比较 |
| `ecbench-app` | CLI、Spring 无 Web 装配、并行编排、兼容日志写入 |

依赖方向：`app → agent → {simulation, opponent, llm} → domain`；`evaluation` 仅依赖 `domain`。

## 快速开始

构建（`verify` 会同时产出可执行 fat jar）：

```bash
mvn clean verify
# fat jar -> ecbench-app/target/ecbench-app.jar
```

运行基准（先设置对应 Provider 的密钥环境变量）：

```bash
export GEMINI_API_KEY=...        # 或 OPENAI_API_KEY / ANTHROPIC_API_KEY 等
java -jar ecbench-app/target/ecbench-app.jar --model gemini-3.5-flash --max-days 10 --max-turns 50
java -jar ecbench-app/target/ecbench-app.jar --model gpt-5.6-sol --runs 5 --seed 42
```

应用以 Spring Boot `WebApplicationType.NONE` 启动（不监听任何端口）。

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

## 模型与 Provider 密钥

模型键见 `models_config.json`（例如 `gpt-5.6-sol`、`claude-opus-4-8`、`gemini-3.5-flash`、`qwen3.7-max`、`glm-5.2-max`、`kimi-k3`、`deepseek-v4-pro` 等）；其中 `npc_tools` 是供应商角色扮演模型（仅渲染对话）。

密钥**一律从环境变量读取**（`models_config.json` 内以 `${ENV}` 形式引用，因此该文件可安全提交）：

```
OPENAI_API_KEY   ANTHROPIC_API_KEY   GEMINI_API_KEY   OPENROUTER_API_KEY
DASHSCOPE_API_KEY   ZHIPU_API_KEY   MOONSHOT_API_KEY   DEEPSEEK_API_KEY
```

## 资源与配置

仓库自带运行期资源副本（无需仓库外任何文件即可运行）：

- `data/`：`products.csv`、`suppliers.csv`、`category_params.csv`、`events.csv`、`promotions.csv`、`store_types.csv`、`store_playbook.json`
- `models_config.json`：模型注册表
- `tokenizer/tokenizer.json`：HuggingFace 分词器

**配置优先级**：`默认值 < models_config < 环境变量 < 命令行`。

可用环境变量覆盖：`ECBENCH_MODELS_CONFIG`、`MODEL_EFFORT`、`TOKENIZER_PATH`、`ECBENCH_CONTEXT_TRIGGER`、`ECBENCH_CONTEXT_CLEAR_AT_LEAST`、`ECBENCH_CONTEXT_KEEP_TOOL_USE`。也可放置 `models_config.local.json`（优先于 `models_config.json`，不纳入版本控制）。

## 运行产物

写入 `log/<timestamp>_<model>/`（与 Python 兼容的布局）：

- `trajectories/run_<i>_messages.jsonl`、`run_<i>_output.log`
- `balance/run_<i>_daily_balance.csv`（`total_balance` 为权威列）
- `metrics/run_<i>_negotiation_metrics.json`、`run_<i>_analysis.json`

其中 `analysis.json` 含 7 个面板：`reward`、`profitability`、`negotiation_quality`、`fraud_identification`、`supplier_engagement`、`return_management`、`fulfilment_quality`。

## 评估子命令

`evaluation/*.py` 的 Java 端口：

```bash
# 绘制余额曲线 PNG（纵轴用权威列 total_balance）
java -jar ecbench-app/target/ecbench-app.jar evaluate plot \
     --input log/<session>/balance/run_0_daily_balance.csv \
     --output log/<session>/plots/balance.png --overlay

# 按供应商提取 chatbox 会话
java -jar ecbench-app/target/ecbench-app.jar evaluate extract-chatbox \
     --input log/<session>/trajectories/run_0_messages.jsonl \
     --output log/<session>/chatbox

# 跨会话比较 analysis 指标
java -jar ecbench-app/target/ecbench-app.jar evaluate compare log/<sessionA> log/<sessionB>
```

## 测试与质量门

```bash
mvn clean verify
```

- 全 7 模块共 **202 个测试**（含离线 E2E 与 Python↔Java 双向兼容测试）。
- **Spotless**（google-java-format）+ **Checkstyle**（行宽 120、禁 star import、方法 ≤150 行）。
- **JaCoCo** 行覆盖率门：`domain` / `simulation` / `opponent` 均须 ≥ 85%，未达标即 `BUILD FAILURE`。

## 与 Python 参考实现的关系

本项目是 E-CommerceBench（Python）的**等价重写**：CLI 参数、环境变量、`log/` 产物布局与 Python 兼容，并经 `PythonCompatibilityTest` 双向验证（Java 读 Python 写出的产物、Python 评估脚本读 Java 写出的产物）。行为上采用 Java 自有的固定种子确定性（同 `seed` 可复现，但不与 Python 逐帧一致），并使用日级推进模型。
