# 论文能力还原设计：§E.6 学习维度指标 + Dashboard 六维视图（浅色 / 全站中文）

- 日期：2026-10-12
- 状态：待评审
- 前置：`docs/superpowers/specs/2026-10-10-serve-web-dashboard-design.md`（serve 看板已实现并在 main 上）
- 范围：两个按序执行的子项目
  1. **子项目 1**：补齐论文 §E.6「长周期学习」全套指标（落盘逐谈判原始记录 → 评估层重算），雷达学习轴切到论文口径 `AnchorRatio`
  2. **子项目 2**：dashboard 还原论文 §3.7/§4.3/附录 E–F 的组织方式（六维度视图、各维度排名与 rank crossings、三类诊断图、资产倍数主评分），整站浅色主题 + 全站中文，chatbox 界面中文化并标注供应商欺诈类型

## 1. 背景：缺口判定依据

对照论文与现网代码/产物后的事实（均已核验）：

| 论文能力 | 现状 |
|---|---|
| 主评分＝年末总资产 ÷ ¥100,000（资产倍数） | 已在产物：`reward.final_score = finalTotalBalance / initial`；`balance` CSV 的 `total_balance` 已是 bank+wallet+escrow 权威列。看板未使用该口径、未如此标注 |
| 六维画像雷达（Figure 2） | 已有七轴雷达（primary + 6） |
| 各维度分别排名 + rank crossings（F.8） | 缺：只有按 primary 的单一排名 |
| 六维度分组诊断（§4.3.1–4.3.6） | 缺：29 行扁平指标矩阵，无维度归类 |
| 八类活动带 / 五类欺诈支出 / 退货四层 | 数据已在 `analysis.json`（`calls_by_band_share`、`spend_by_fraud_type`、`exp_return_rate_*`），无可视化 |
| §E.6 学习维度：`AnchorRatio`/`AnchorRegret`/`NewLow`/`z_anchor`/三个半年比较 | 缺：`NegotiationTracker.anchoringMetrics()` 只算 `anchor_regret`（无论文四项过滤、无 `NewLow`、无置换零分布、无比率与 z），且 `anchoring` 只写进 `negotiation_metrics.json`，**未写进 `analysis.json`**，评估层看不到；雷达学习轴用 `se_half_lift` 代理（旧 spec 已记为偏差） |
| 失败案例阅读（附录 G） | 有逐供应商对话，无欺诈类型标注 |

硬约束（必须知悉）：`log/` 现有 4 个会话均为 **2 天、2 场谈判、0 次重复采购、0 笔销售、0 退货**。补完产物层后，学习维度与运营执行维度在这些产物上仍为空态/全 0。要在页面上看到学习维度真实数字，需重跑一个长 episode（见 §8）。

## 2. 非目标（YAGNI）

- 不改 Python 侧任何产物格式；`analysis.json` / `negotiation_metrics.json` / `daily_balance.csv` / `messages.jsonl` 的既有字段与取值**零改动**（新产物为纯 additive 文件）。
- 不改 `RunComparator.METRIC_DEFS`（29 行与 Python `compare_runs` 对齐，保持 CLI 比较表形状不变）。
- 不实现 §E.6 之外的新指标；不重定义任何既有指标口径。
- 不在页面上发起 run、不做写操作、不做鉴权/数据库/外网暴露（沿用 serve 既有约束）。
- 不引入前端框架、构建步骤或外部图表库（继续零依赖纯 SVG）。
- 不做 run 选择器（保留旧 spec 记录的已知偏差：下钻固定 `run_0`）。
- 不写 JS 单测（沿用既有裁定：前端以浏览器人工验证 + 控制台零报错为准）。

## 3. 论文口径（实现的唯一依据）

### 3.1 §3.7 六维度排名指标

| 维度 | 排名指标 | 方向 | 计算式（取自 `analysis.json`） |
|---|---|---|---|
| 主评分 primary | 年末总资产倍数 | 越高越好 | `reward.final_score` 各 run 均值 |
| 谈判质量 negotiation | `CSE+` | 越高越好 | `negotiation_quality["CSE+"]` |
| 欺诈规避 fraud | `BadSpend%` | 越低越好 | `fraud_identification.spend_on_bad_supplier_share` |
| 现金流与偿付 solvency | 回撤比 | 越低越好 | `profitability.peak_drawdown / profitability.peak_total_assets`（分母 0 → null） |
| 运营效率 efficiency | 利润 / 工具调用 | 越高越好 | `(profitability.final_balance − profitability.initial_balance) / operational_efficiency.total_tool_calls`（分母 0 → null） |
| 运营执行 execution | 可控退货率 | 越低越好 | `return_management.controllable_return_rate` |
| 长周期学习 learning | `AnchorRatio` | 越低越好 | 见 §3.2（不再用 `se_half_lift` 代理） |

会话级取值一律为「该会话各 run 的均值」；`AnchorRatio` 按论文取「各 episode 比率的均值」，不是「汇总均值之比」。

### 3.2 §E.6 序贯价格纪律（anchoring）

对单个 episode（= 单个 run）：

1. 取全部**诚信**（`supplier_type == "good"`）且**已成交**（`outcome == "Agreement"` 且 `final_price != null`）的谈判，按 `(supplier, sku_id)` 分组为 pair 集合。
2. 四项过滤：① 欺诈 pair 剔除；② `day_concluded` 缺失的成交剔除；③ `Δ_P = reference_price − cost_floor ≤ 0` 的 pair 剔除；④ 成交次数 `n_P < 2` 的 pair 不贡献。过滤后无 pair → 本 episode 全部学习指标为 null。
3. 每 pair 内按 `day_concluded` 升序（同日按 jsonl 出现序稳定排序），位置与历史最优：
   - `π_t = min(1, max(0, (p_t − cost_floor) / Δ_P))`，`t = 1..n_P`
   - `m_t = min_{u<t} π_u`，`t = 2..n_P`
4. 水平统计（`R` = 全部 re-order 集合，`|R| = Σ_P (n_P − 1)`；两项均不按订单规模加权）：
   - `AnchorRegret = (1/|R|) Σ_P Σ_{t=2..n_P} max(0, π_t − m_t)`
   - `NewLow = (1/|R|) Σ_P Σ_{t=2..n_P} 1[π_t < m_t − ε]`，`ε = 0.01`
5. 置换零分布：`B = 400` 个 seeded 重复；每个重复 `b` 内**每 pair 独立**置换其 `π` 序列（Fisher–Yates），重算同一统计量得 `AnchorRegret^(b)` / `NewLow^(b)`：
   - `μ_r = (1/B) Σ_b AnchorRegret^(b)`，`σ_r` = 同一组 B 个值的**总体**标准差（除以 B；与档案其他地方的样本标准差不同，两者不可比较）
   - `μ^new_r`、`σ^new_r` 同构，作用于 `NewLow`
   - `AnchorRatio_r = AnchorRegret_r / μ_r`（`μ_r == 0` → null）
   - `z_r = (μ_r − AnchorRegret_r) / σ_r`（`σ_r == 0` → null）
   - `NewLowRatioZ_r = (NewLow_r − μ^new_r) / σ^new_r`（`σ^new_r == 0` → null）
6. 跨 episode 合并（会话级）：`k` = 该会话内「有重复 pair 且零分布非退化」的 run 数；`z_anchor = (1/√k) Σ_r z_r`（Stouffer）；`AnchorRatio`、`AnchorRegret`、`NewLow`、`NewLowRatioZ` 取各 run 均值。

确定性要求：pair 迭代序为 key（`supplier + "\u0000" + sku_id`）的字典序；随机源为 `new java.util.Random(seed)`，`seed` 默认 `20260101`、可构造注入。同输入 + 同 seed 必须逐位相同。

### 3.3 §E.6 半年比较

`Z_G` = 该 episode 全部诚信**已结束**会话（成交与未成交都算），`n = |Z_G|`：

- `s_z = 1[f_z ≠ ⊥] · SE_z`（未成交记 0 分，与 `SE+` 口径一致；`SE_z = (reference_price − final_price) / Δ`，`Δ ≤ 0` 记 0）
- `n ≥ 4` 才计算，否则 null；按 `day_concluded` 升序，早半 `E` = 前 `⌊n/2⌋` 个，晚半 `L` = 其余（奇数时多的一个归晚半）
- `h_SE = mean_{L}(s_z) − mean_{E}(s_z)`
- `b_SE = Σ (d_z − d̄)(s_z − s̄) / Σ (d_z − d̄)²`（在**整个** `Z_G` 上最小二乘；全部会话同日结束 → 0）
- `h_fraud`：在**全部**已结束会话（含欺诈）上计算，按该集合**自身**的日期中位数切分为 `E_A`/`L_A`；`β(S) = |{z ∈ S ∩ Z_B : f_z ≠ ⊥}| / |S|`；`h_fraud = β(E_A) − β(L_A)`（正 = 改善）。样本 < 4 → null。中位数切分：日期升序后取中位日 `d*`，`d_z ≤ d*` 归早半，其余归晚半。

三项均以「正 = 改善」为方向落盘，前端按此着色。

## 4. 子项目 1 设计

### 4.1 新产物契约

`metrics/run_{i}_negotiation_records.jsonl`，UTF-8，每行一个 JSON 对象（一条**已完成**谈判），字段固定：

| 字段 | 类型 | 来源 | 备注 |
|---|---|---|---|
| `supplier` | string | `NegotiationRecord.supplierName()` | pair 分组键之一 |
| `sku_id` | string | `skuId()` | pair 分组键之一 |
| `supplier_type` | string | `supplierType()` | `good` / `bad` |
| `supplier_family` | string\|null | `supplierFamily().displayName()` | 诊断用，不参与计算 |
| `reference_price` | number | `referencePrice()` | `v` |
| `cost_floor` | number | `costFloor()` | `c` |
| `initial_offer` | number | `initialOffer()` | 诊断用 |
| `final_price` | number\|null | `finalPrice()` | `p`；未成交为 null |
| `outcome` | string | `outcome()` | `Agreement` / `Disagreement` |
| `terminated_by` | string\|null | `terminatedBy()` | |
| `day_started` | number\|null | `dayStarted()` | |
| `day_concluded` | number\|null | `dayConcluded()` | `d` |
| `rounds` | number | `rounds()` | |
| `critical_violations` | string[] | `criticalViolations()` | |
| `agent_prices` | number[] | `agentPrices()` | 供后续审计/研究，本次计算不使用 |
| `supplier_prices` | number[] | `supplierPrices()` | 同上 |

写入规则：`Money` 一律以 `amount().doubleValue()` 落盘；`indent` 不美化（每行一条，与 `messages.jsonl` 风格一致）；无已完成谈判时写出**空文件**（保持产物存在性可判定）；写入失败包装 `UncheckedIOException`。

### 4.2 代码改动清单（子项目 1）

`ecbench-app`（4 空格）：
- `log/RunDirectory.java`：新增 `negotiationRecordsJsonl(int runIndex)` → `metricsDir().resolve("run_" + i + "_negotiation_records.jsonl")`
- `log/NegotiationRecordJsonlWriter.java`（新）：`AutoCloseable`，构造接收 `Path` + `ObjectMapper`，`writeAll(List<NegotiationRecord>)` 一次性写出，`close()` 幂等
- `log/CompositeRunObserver.java`：构造新增 `Supplier<List<NegotiationRecord>> recordsSupplier`；`onRunComplete` 中于 `writeNegotiationMetrics` 之后写 jsonl；`onFailure` 中尽力写（异常吞掉，不得掩盖原始异常，与既有 metrics 行为一致）；`close()` 关闭新写入器
- `run/RunComponentFactory.java`：把 `tracker::completed` 作为新参数传入 `CompositeRunObserver`

`ecbench-evaluation`（4 空格）：
- `io/RunLayoutResolver.java`：`RunLayout` 新增 `negotiationRecordsJsonl(int)`
- `model/NegotiationRecordRow.java`（新）：jsonl 一行的不可变载体（record），字段同 §4.1；缺失字段容忍为 null / 空列表
- `io/NegotiationRecordJsonlReader.java`（新）：`List<NegotiationRecordRow> read(Path)`；文件不存在 → 空列表；坏行/半写入行**跳过不中断**（与 `ChatboxExtractor` 一致）
- `model/LearningMetrics.java`（新）：单 run 结果 record —— `anchorRegret`、`newLow`、`anchorRatio`、`zEpisode`、`newLowRatioZ`、`nullMean`、`nullStd`、`seHalfLift`、`seTrend`、`fraudHalfLift`、`repeatPairs`、`reorders`（全部 `Double`，计数为 `int`；不适用 → null / 0）
- `model/SessionLearning.java`（新）：会话级聚合 record —— `anchorRatio`、`anchorRegret`、`newLow`、`newLowRatioZ`、`zAnchor`、`seHalfLift`、`seTrend`、`fraudHalfLift`、`episodes`（= `k`）、`repeatPairs`、`reorders`
- `LearningMetricsCalculator.java`（新）：`LearningMetrics calculate(List<NegotiationRecordRow> rows)`；构造 `LearningMetricsCalculator()`（seed = 20260101, B = 400）与 `LearningMetricsCalculator(long seed, int replicates)`；纯函数、无 IO、无静态可变状态
- `LearningMetricsService.java`（新）：`SessionLearning forSession(Path sessionDir)` —— 用 `RunLayoutResolver` 定位并按 run 序号升序读取全部 jsonl，逐 run 调 calculator，再按 §3.2 第 6 条聚合；该会话无任何 jsonl 或全部 run 无重复 pair → 返回 null
- `model/ComparisonReport.java`：`SessionComparison` 新增可空组件 `SessionLearning learning`；**保留**既有 3 参构造（委托为 `learning = null`），既有调用方与测试零改动
- `RunComparator.java`：构造新增可注入 `LearningMetricsService`（无参构造用真实实现）；`compare()` 为每个会话填充 `learning`。`METRIC_DEFS` 不变
- `ProfileAxis.java`：`LEARNING` 的 label 改 `Learning (AnchorRatio)`、`lowerIsBetter = true`、`value()` 改 `session.learning() == null ? null : session.learning().anchorRatio()`；类 Javadoc 删去「用 `se_half_lift` 代理」的偏差说明，改为记录新口径

`ecbench-opponent`：**零改动**（`NegotiationTracker.anchoringMetrics()` 的 `anchor_regret` 保留原样，作为交叉校验参照，继续写进 `negotiation_metrics.json`）。

### 4.3 兼容性与不变量

- 旧产物（无 jsonl，含现有 `log/` 四个会话）：`learning == null` → 雷达学习轴 null（不闭合，前端已降级）、看板学习卡显示「不适用」；`/api/compare` 的 `sessions`/`radar`/`ranking` 三段与改动前**逐字段一致**。
- 有意行为变更（须记录）：`evaluate` CLI 的雷达/排名在**旧产物**上学习轴由「`se_half_lift` 代理值」变为空。这是用户已批准的口径切换的直接后果，不做回退兼容。
- `SessionComparison.metrics` 的 label 集合不变（学习指标不混入指标矩阵，避免 CLI 比较表形状变化）。

### 4.4 测试计划（子项目 1，TDD 先红后绿）

- `LearningMetricsCalculatorTest`：
  - 手算 fixture A（2 个诚信 pair，各 3 次成交，价格使 π 可手算）→ 断言 `anchorRegret`、`newLow` 精确到 4 位小数
  - 同 seed 两次 `calculate` 结果**逐位相同**；不同 seed 的 `nullMean` 不同（证明置换真的生效）
  - `reorders == Σ(n_P − 1)`、`repeatPairs` 计数正确
  - 过滤：欺诈 pair 剔除、`day_concluded == null` 剔除、`Δ ≤ 0` 剔除、`n_P == 1` 不贡献；全被剔除 → 所有指标 null 且 `repeatPairs == 0`
  - 退化：`μ_r == 0`（全部 π 相同）→ `anchorRatio` null；`σ_r == 0` → `zEpisode` null
  - 半年比较：`n < 4` → `seHalfLift` null；奇数 `n` → 多的一个归晚半；全同日 → `seTrend == 0`；`h_fraud` 方向（构造晚半欺诈成交更多的样本 → 负值）
- `NegotiationRecordJsonlReaderTest`：round-trip（写出→读回字段等值）、文件不存在 → 空列表、坏行跳过、缺字段容忍
- `NegotiationRecordJsonlWriterTest`（app 层，`@TempDir`）：写出行数 == 记录数；空列表 → 空文件；`Money` 精度落盘正确
- `CompatibleRunLoggingTest` 扩展（仓库**无** `CompositeRunObserverTest`；该类已持有构造 observer 与 `RunResult` 的现成写法）：`onRunComplete` 生成 jsonl（空记录 → 空文件）；`onFailure` 也生成 jsonl 且不抛。另一处调用点 `CompositeRunObserverToolCallCountTest` 只需补第 6 个构造参数（`List::of`）
- `RunComparatorTest` 扩展：有 jsonl → `learning != null` 且 `anchorRatio` 与 calculator 一致；无 jsonl → `learning == null` 且 metrics 29 行与改动前一致
- `ProfileAxisTest` 扩展：`LEARNING.lowerIsBetter() == true`；`learning == null` → `value()` 为 null；有值 → 取 `anchorRatio`

受口径切换影响、**必须同步修改**的既有测试（已逐个核验）：

- `ProfileAxisTest`：现有断言 `LEARNING.lowerIsBetter() == false`（L56）与 `LEARNING.value(session) == 0.05`（取自 `se_half_lift`，L35/L73）均会红，改为新口径断言
- `EvaluationCommandProfileTest`：已核验只断言「PNG 存在且非空」与 CSV 表头 `rank,model,primary_raw`，**不断言学习轴取值**；`RadarPlotter` 将 NaN 回退为 0.0（L97）→ 口径切换后仍通过，无需修改
- `CrossModelProfilerTest`：已核验不涉及 `learning`/`se_half_lift`，且经 3 参 `session(...)` 助手构造 `SessionComparison`（保留旧构造）→ 无需修改
- `RankingTableTest`：只断言轴 key 集合（含 `learning`），key 本次不改 → 无需修改
- `CompatibleRunLoggingTest` / `AnalysisGoldenParityTest` / `PythonCompatibilityTest`：已核验均**不断言产物文件集合**（只断言各文件内容与表头），新增 `negotiation_records.jsonl` 不影响其通过
- JaCoCo 行覆盖率 ≥ 0.85 的门禁仅在 `ecbench-domain`/`ecbench-opponent`/`ecbench-simulation` 三个 pom 内配置；本次只改 `ecbench-evaluation` 与 `ecbench-app`（无覆盖率硬门）且 `ecbench-opponent` 零改动 → 不触发覆盖率风险

## 5. 子项目 2 设计

### 5.1 后端契约

`ecbench-evaluation`（4 空格）：
- `io/SupplierRosterReader.java`（新）：`Map<String, RosterEntry> readByEmail(Path suppliersCsv)`；只读 `suppliers.csv` 一张表（**不用** `CsvCatalogLoader.load()`——它要求六张表齐全且任一缺失即抛，不适合只读看板）；文件缺失/表头缺列/坏行 → 跳过并在 stderr 记一次警告，返回已解析部分；`RosterEntry(String email, String name, String type, String personality, String fraudType)`

`ecbench-app/web`（4 空格）：
- `DimensionService.java`（新）：输入 `List<Path> sessionDirs`，自行用 `AnalysisJsonReader` + `RunLayoutResolver` 读各 run 的 `analysis.json`，按 §3.1 口径算六维度排名指标与会话均值、每维度 rank、`primaryScore`、`rankCrossings`；输入 `Map<String, SessionLearning>` 提供学习维度值。与 `CompareService` 并列、互不依赖（各自读一次小 JSON，代价可忽略；换来职责单一与可独立单测）
- `CompareService.java`：`compare()` 返回体新增 `dimensions`、`primaryScore`、`rankCrossings`、`learning` 四段（既有 `sessions`/`radar`/`ranking` 三段形状与取值不变）
- `RunArtifactService.java`：`chatbox()` 每供应商节点新增 `supplierType`/`personality`/`fraudType`（未命中 roster → 三字段均为 JSON null）；构造新增可空 `Map<String, RosterEntry> roster`
- `DashboardServer.java`：构造新增可空 roster 参数并透传
- `ServeCommand.java`：新增 `@Option(names = "--catalog")`，默认 null → 解析为 `ResourcePaths.of(Path.of(System.getProperty("user.dir"))).dataDir(null).resolve("suppliers.csv")`；解析失败或文件不存在 → roster 为空 map，stderr 记一行提示，**服务照常启动**

`/api/compare` 响应新增段（JSON 形状即契约）：

```json
{
  "primaryScore": {
    "labelZh": "年末总资产倍数", "unit": "×", "sourceField": "reward.final_score",
    "rows": [{ "name": "会话名", "value": 0.9521, "rank": 1 }]
  },
  "dimensions": [{
    "key": "negotiation", "labelZh": "谈判质量", "paperRef": "§4.3.1 / §E.1",
    "rankMetric": { "label": "CSE+", "labelZh": "已成交剩余效率", "direction": "higher", "unit": "" },
    "rows": [{ "name": "会话名", "value": 0.5005, "rank": 1 }],
    "diagnostics": [{ "label": "SE+", "labelZh": "剩余效率", "values": { "会话名": 0.5005 } }]
  }],
  "rankCrossings": [{ "name": "会话名", "primary": 1,
    "ranks": { "negotiation": 1, "fraud": 2, "solvency": 1, "efficiency": 3, "execution": 2, "learning": null } }],
  "learning": { "会话名": { "anchorRatio": null, "anchorRegret": null, "newLow": null,
    "newLowRatioZ": null, "zAnchor": null, "seHalfLift": null, "seTrend": null,
    "fraudHalfLift": null, "episodes": 0, "repeatPairs": 0, "reorders": 0 } }
}
```

取值与排名规则（写死，避免歧义）：

- 会话级取值一律为「该会话各 run 的均值」，null/缺失 run **跳过不计入分母**（与 `RunComparator` 的 null 处理一致），绝不按 0 计入；全部 run 均 null → 该会话该维度 `value = null`。
- rank 1 = 该维度最好（按 `direction` 排序）；并列取 competition ranking（1,2,2,4）；`value == null` 的会话排末位且 `rank = null`。
- 分母为 0 的派生口径（回撤比的 `peak_total_assets`、效率的 `total_tool_calls`）→ `value = null`，不用 `analysis.json` 里已算好的 `profit_per_tool_call` 顶替（后者分母为 0 时本就是 null，两者一致，但统一由 `DimensionService` 从原始字段重算，避免两套口径）。
- `learning` 段的 key 为会话名；`SessionLearning == null` 时该会话**仍出现**在 `learning` 中，全部数值字段为 null、计数字段为 0（保证前端形状稳定）。

六个维度的 `diagnostics` 明细：

| 维度 | diagnostics（label → `analysis.json` 路径） |
|---|---|
| negotiation | `SE+`、`%Oracle`、`AGR+`、`avg_rounds`、`money_saved`（`negotiation_quality.*`） |
| fraud | `bad_order_share`、`spend_on_bad`、`vip_fee_paid_count`、`vip_fee_paid_amount`、`contacted_bad`、`ordered_bad`（`fraud_identification.*`、`supplier_engagement.*.distinct_bad`）；另附 `spendByFraudType`（五类骗局的 orders/units/spend） |
| solvency | `peak_drawdown`、`peak_total_assets`、`bankrupt`、`final_day`（`profitability.*`） |
| efficiency | `profit_per_tool_call`、`tool_calls`、`turns`、`context_evictions`、`memory_calls`（`operational_efficiency.*`）；另附 `callsByBand`（八类活动带计数与占比） |
| execution | `controllable_return_rate`、`realized_return_rate`、`on_time_ship`、`refund_loss_total`、`shipping_loss_on_returns`；另附 `returnLayers`（`exp_return_rate_natural` / `_from_pricing` / `_from_ship_speed` / `_from_defective_fraud`）与 `ship_speed_counts` |
| learning | 由 `learning` 段承载（`anchorRatio`/`anchorRegret`/`newLow`/`newLowRatioZ`/`zAnchor`/`seHalfLift`/`seTrend`/`fraudHalfLift`/`episodes`/`repeatPairs`/`reorders`） |

`/api/sessions/{name}/chatbox?run=i` 的 `suppliers[]` 每项新增 `supplierType`/`personality`/`fraudType`；其余字段与既有形状不变。

### 5.2 前端：浅色主题（只换配色，版式与结构不动）

`app.css` 的 `:root` 全量替换为浅色调色板（实现即按此取值）：

```css
--bg: #f5f6f8;      --bg-card: #ffffff;   --bg-inset: #eef1f5;
--line: #d7dce3;    --line-soft: #e6e9ee;
--ink: #1b2430;     --ink-dim: #55606e;   --ink-faint: #8a94a2;
--amber: #b0761a;   --cyan: #26808f;      --green: #3d8b50;   --red: #bd4136;
```

- `body` 两处 radial-gradient 透明度降到 `.05`/`.04`，两处 repeating-linear-gradient 的 `rgba(255,255,255,.014)` 改为 `rgba(16,24,40,.03)`
- 卡片阴影：`0 1px 2px rgba(16,24,40,.06), 0 1px 3px rgba(16,24,40,.10)`；不使用内发光
- `app.js` 的 `PALETTE` 换为浅底高对比 8 色：`['#b0761a','#26808f','#3d8b50','#bd4136','#6b4fa1','#9a7d12','#3d6fa8','#a44f7d']`
- SVG 内网格线/轴线/文字色改为读 CSS 变量（`getComputedStyle`）或新常量 `GRID = '#e6e9ee'`、`AXIS = '#8a94a2'`、`LABEL = '#55606e'`；median 虚线用 `#8a94a2`
- chatbox 消息气泡：底色改 `--bg-inset`，左侧 3px 语义色边框区分角色（智能体 = `--cyan`、供应商回复/工具结果 = `--amber`、系统 = `--ink-faint`）；`.who` 标签文字用对应语义色，不再用半透明白底
- 收尾必须逐条 grep 核对残留深色：`#0b0d10|#11151b|#0e1217|#1f262e|#171d24|#e9e6df|rgba(255,\s*255,\s*255`，命中数为 0
- 版式、栅格（`span-5`/`span-7`/`span-12`）、字体族（serif display + mono）、reveal 动效、tooltip 结构一律不动

### 5.3 前端：全站中文（术语保留英文）

规则：所有导航、卡片标题、KPI 名、表头、按钮、提示、空态、错误、`aria-label` 用中文；论文/产物原始术语保留英文并紧跟中文注解，格式 `EN（中文）`。固定译名表（实现按此，不得自创）：

| 英文 | 中文注解 |
|---|---|
| primary score | 主评分 |
| final_balance | 年末总资产 |
| CSE+ | 已成交剩余效率 |
| SE+ | 剩余效率 |
| %Oracle | 预言机归一化剩余 |
| AGR+ | 诚信供应商成交率 |
| BadSpend% / spend_on_bad_supplier_share | 欺诈支出占比 |
| peak drawdown / peak total assets | 峰值回撤 / 峰值总资产 |
| controllable return rate | 可控退货率 |
| AnchorRatio | 锚定比 |
| AnchorRegret | 锚定遗憾 |
| NewLow | 创新低率 |
| z_anchor | 锚定 z 分数 |
| surplus half-lift (h_SE) | 剩余半期提升 |
| per-day trend (b_SE) | 逐日盈余趋势 |
| fraud half-lift (h_fraud) | 欺诈半期提升 |
| activity bands | 活动带 |
| context evictions | 上下文驱逐 |
| rank crossings | 排名交叉 |
| drill down | 下钻 |
| chatbox 角色 assistant / tool / user | 智能体 / 供应商回复·工具结果 / 系统 |

侧栏品牌区副标题 `run telemetry console` → `运行遥测看板`；页面 `<title>` → `E-CommerceBench · 运行遥测看板`；`<html lang="zh">` 保持。

### 5.4 前端：面板清单与数据来源

自上而下（`①`–`④` 为新增，其余为保留并中文化）：

1. **① 主评分卡区**：`primaryScore`（资产倍数，`×` 单位，2 位小数）+ 该会话 `final_balance` 均值 + 破产 run 数 + 终止原因（`reward.termination_reason/detail`，取自下钻的 analysis）。KPI 四张卡改为：最佳主评分（倍数）/ 已对比会话数（含 run 数与有数据会话数）/ 有数据轴数（of 7）/ 工具调用均值
2. **② 六维度卡片网格**（§4.3.1–4.3.6 顺序，每卡：标题中文 + `paperRef` + 排名指标值与该维度 rank + diagnostics 小表）：
   - 效率卡内嵌**八类活动带柱状图**（SVG 横向条，数据 `callsByBand`，条旁标 `count (share%)`，固定带序 `waiting/shipping/listing/withdraw/bargaining/state_polling/memory/remainder`）
   - 执行卡内嵌**退货四层分解条**（SVG 堆叠横条，数据 `returnLayers`，四层中文注解：自然退货 / 定价过高 / 配送过慢 / 欺诈次品）
   - 欺诈卡内嵌**五类骗局支出堆叠条**（数据 `spendByFraudType`，键序 `fake_urgency/future_discount/qty_bait/quality_downgrade/vip_fee`）+ 会员费（次数与金额）+ 接触/下单过的欺诈供应商数
   - 学习卡展示 `AnchorRatio`（主）、`AnchorRegret`、`NewLow`、`z_anchor`、三个半年比较、`episodes/repeatPairs/reorders` 计数
3. **③ rank crossings 表**（F.8）：行 = 会话，列 = 主评分 + 六维度 rank；与该会话主评分 rank 不一致的格子加醒目标记（浅色主题下用 `--red` 左边框 + 淡底）；`null` 显示 `—`
4. **④ chatbox 供应商标签**：分组标题 `供应商名 · N 条 · [欺诈·vip_fee]` 或 `[诚信·Friendly]`；roster 未命中则不显示标签
5. 保留：**七轴雷达**（轴标签改中文注解 + 学习轴切 `AnchorRatio`，图例与 note 中文）、**余额曲线叠加**（`total_balance` 权威列，说明文字标注「= 银行 + 平台钱包 + 未结算托管」）、**指标矩阵**（按六维度分组重排为可折叠分组，保留过滤框；分组标题中文，行 label 保留英文）、**analysis.json 原始下钻**（分组名中文注解，键名保留英文原文）

### 5.5 指标矩阵的维度分组映射

指标矩阵按六维度重排为可折叠分组，`METRIC_DEFS` 的 29 个 label 归组固定如下（前端常量，不得由实现者临场判断）：

| 分组（中文标题） | 归属 label |
|---|---|
| 谈判质量 | `SE+`、`CSE+`、`%Oracle`、`AGR+`、`avg_rounds`、`money_saved` |
| 欺诈规避 | `bad_order_share`、`spend_on_bad`、`spend_bad_share`、`vip_fee_paid` |
| 现金流与偿付 | `final_balance`、`initial_balance`、`peak_total_assets`、`peak_drawdown`、`bankrupt`、`final_day`、`stores / reopens` |
| 运营效率 | `tool_calls`、`profit_per_tool_call`、`context_evictions`、`memory_calls` |
| 运营执行 | `on_time_ship`、`return_rate`、`controllable_return_rate` |
| 长周期学习 | `se_half_lift`、`agr_half_lift`、`fraud_avoid_lift`、`time_to_zero_bad`、`rounds_improvement` |

分组内行序保持 `METRIC_DEFS` 原序；过滤框对 label 与中文分组标题同时生效。

**学习维度的双口径说明（必须在前端明示，避免误读）**：矩阵「长周期学习」组的 5 行来自 `analysis.json` 的 `negotiation_quality.learning_speed`（追踪器口径，为保持 Python 对齐而不动）；学习维度卡展示的是 §E.6 重算值（来自逐谈判记录）。两者中的「剩余半期提升」理论上同源（同一批诚信会话），仅排序口径可能不同：当二者都有值且差的绝对值 > 1e-6 时，学习卡 note 追加中文提示 `与追踪器 learning_speed.se_half_lift 存在差异（排序口径），以本卡 §E.6 重算值为准`；否则不提示。

空态文案（中文 + 论文条款）示例：
- 学习卡无数据：`该会话无重复采购记录，AnchorRatio 不适用（§E.6 过滤规则 4）`
- 旧产物无 jsonl：`该会话产物早于逐谈判记录落盘，学习维度不可计算（需重跑基准）`
- 执行维度全 0：`该会话无销售与退货样本，运营执行维度为 0（非缺失）`
- roster 缺失：`未找到供应商名册（data/suppliers.csv），已跳过欺诈标注`

## 6. 质量门与代码风格

- 缩进镜像所在模块既有风格：`ecbench-app` / `ecbench-evaluation` / `ecbench-opponent` 为 **4 空格**，`ecbench-domain` 为 2 空格（本次不改 domain）。
- **禁止运行 `mvn spotless:apply`**（旧 ledger 裁定：它按默认 GOOGLE 2 空格重排整模块，产生巨量无关 diff；且 Spotless 门禁在本特性开工前即为红）。门禁验证用「编译 + 模块测试 + Checkstyle」。
- Checkstyle：行宽 ≤ 120、禁 star import、单方法 ≤ 150 行；新增类补中文 Javadoc。
- 提交策略：沿用用户既有授权，在 `main` 上按任务小步提交。
- 实施分两阶段：**阶段 A = 子项目 1**（产物落盘 + §E.6 计算器 + 轴口径切换），**阶段 B = 子项目 2**（后端维度段 + 前端浅色/中文/新面板）。阶段 B 对阶段 A 的字段一律优雅降级，故两阶段可分别验收、分别演示；顺序不得颠倒（B 依赖 A 的 `SessionLearning` 契约）。
- 收尾：`mvn clean verify`（不带 skip）须全模块 BUILD SUCCESS、0 Checkstyle 违规；如实报告 Spotless 基线状态（不恶化即可）。

## 7. 验收标准（Definition of Done）

1. `mvn clean verify` 全绿，0 Checkstyle 违规；新增测试全部通过，且每个新类都有先红后绿的证据。
2. 一次真实或合成 run 后生成 `metrics/run_{i}_negotiation_records.jsonl`，字段齐备且可被 reader 读回（round-trip 测试）。
3. 合成 fixture（≥2 个诚信 pair 各 ≥3 次成交 + 1 个欺诈 pair + 1 个 `Δ ≤ 0` pair）下，`AnchorRegret`/`NewLow`/`AnchorRatio`/`z` 与手算值一致（4 位小数），同 seed 两次运行逐位相同。
4. 旧产物回归：以现有 `log/` 四个会话为输入，`/api/compare` 的 `sessions`/`radar`/`ranking` 三段与改动前逐字段一致（自动化断言），`learning` 段为 null 值，页面无报错。
5. `serve` 启动后浏览器验证：浅色主题生效（无深色残留）、全站中文、六维度卡 + rank crossings + 三类诊断图渲染正确、chatbox 角色中文且欺诈标注可见、控制台 0 报错 0 失败请求。
6. 安全性不回归：非法/越界 `{name}` → 400，未知端点/缺失产物 → 404，非 GET → 405；`--catalog` 指向不存在文件时 chatbox 正常返回（标注字段为 null）且服务照常启动。
7. README 的 `serve` 小节更新：新增面板说明、`--catalog` 选项、学习维度口径切换（`AnchorRatio`）与新产物文件说明。

## 8. 演示数据准备（需用户参与的一步）

当前环境无任何 LLM API key（仅有无关的 `TICKFLOW_API_KEY`），实现方无法自行产出真实长 episode 数据。

- 代码完成后立即执行：用 `@TempDir` 合成 fixture（含重复采购、欺诈 pair、销售与退货样本）做端到端演示，保证六维度与学习维度在页面上都有非空数字。
- 真·365 天数据由用户执行（key 与模型键由用户提供）：
  ```bash
  export OPENAI_API_KEY=...   # 或 ANTHROPIC_API_KEY / GEMINI_API_KEY / OPENROUTER_API_KEY
  java -jar ecbench-app/target/ecbench-app.jar --model <models_config.json 中的键> --max-days 365 --log-dir ./log
  ```
  跑完后新会话自动被 `serve` 发现，实现方负责刷新看板做真实数据演示与对照解读。

## 9. 风险与已知偏差

| # | 风险/偏差 | 处置 |
|---|---|---|
| 1 | 学习维度在现有产物上必然为空 | §8 的合成 fixture 保证可演示；真实数据待用户重跑 |
| 2 | `ProfileAxis.LEARNING` 口径切换使旧产物的 CLI 雷达学习轴变空 | 用户已批准的有意变更；README 与 spec 双处记录，不做回退兼容 |
| 3 | 置换零分布 `B = 400` 的耗时 | 每 run 约 `400 × Σ(n_P − 1)` 次基本运算（论文全量 90 episode 也仅 2230 个重复 pair），量级可忽略；calculator 无 IO，可并行但本次不并行 |
| 4 | `SupplierRosterReader` 依赖 `suppliers.csv` 表头列名 | 表头缺列即视为不可用（返回空 map + stderr 警告），看板降级为无标注而非报错 |
| 5 | `DimensionService` 与 `CompareService` 各读一次 analysis.json | 接受：换取职责单一与可独立单测；文件为 KB 级 |
| 6 | 保留的旧偏差：`scan()` 仅按 `metrics/` 计 run（legacy 平铺布局计数偏 0）；下钻固定 `run_0` | 本次不修，沿用旧 spec 记录 |
| 7 | 浅色主题下系列色对比度不足 | §5.2 已给出针对浅底调过的 8 色；收尾用浏览器截图逐图核对 |

## 10. 文件级改动清单（汇总）

新增：
- `ecbench-app/src/main/java/.../app/log/NegotiationRecordJsonlWriter.java`
- `ecbench-app/src/main/java/.../app/web/DimensionService.java`
- `ecbench-evaluation/src/main/java/.../evaluation/LearningMetricsCalculator.java`
- `ecbench-evaluation/src/main/java/.../evaluation/LearningMetricsService.java`
- `ecbench-evaluation/src/main/java/.../evaluation/io/NegotiationRecordJsonlReader.java`
- `ecbench-evaluation/src/main/java/.../evaluation/io/SupplierRosterReader.java`
- `ecbench-evaluation/src/main/java/.../evaluation/model/NegotiationRecordRow.java`
- `ecbench-evaluation/src/main/java/.../evaluation/model/LearningMetrics.java`
- `ecbench-evaluation/src/main/java/.../evaluation/model/SessionLearning.java`
- 对应测试类（`LearningMetricsCalculatorTest`、`NegotiationRecordJsonlReaderTest`、`SupplierRosterReaderTest`、`NegotiationRecordJsonlWriterTest`、`DimensionServiceTest`）与 fixture 构造工具

改动：
- `ecbench-app`：`log/RunDirectory.java`、`log/CompositeRunObserver.java`、`run/RunComponentFactory.java`、`web/CompareService.java`、`web/RunArtifactService.java`、`web/DashboardServer.java`、`cli/ServeCommand.java`、`resources/web/{index.html,app.css,app.js}`
- `ecbench-evaluation`：`ProfileAxis.java`、`RunComparator.java`、`io/RunLayoutResolver.java`、`model/ComparisonReport.java`
- 既有测试：`RunComparatorTest`、`ProfileAxisTest`、`CompatibleRunLoggingTest`、`CompositeRunObserverToolCallCountTest`、`CompareServiceTest`、`RunArtifactServiceTest`、`DashboardServerTest`（新增段与 roster 降级；仓库无 `ApiRouterTest`，路由行为由 `DashboardServerTest` 覆盖）
- `README.md`（serve 小节）

不改：`ecbench-opponent`（全部）、`ecbench-domain`（全部）、`ecbench-simulation`、`ecbench-agent`、`ecbench-llm`、`MetricsJsonWriter.java`、`RunComparator.METRIC_DEFS`、`EcommerceBenchApplication.java`（仍 `WebApplicationType.NONE`）、`run`/`evaluate` 的命令语义。
