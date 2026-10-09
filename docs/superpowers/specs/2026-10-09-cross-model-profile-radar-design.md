# 设计：`evaluate profile` — 七轴雷达图 + 跨模型排名

日期：2026-10-09
范围：为 `ecbench-evaluation` 新增跨模型能力画像子系统，复现论文 §3.7 / 附录 E、Figure 2 的七轴雷达图与跨模型归一化排名。

## 背景与目标

论文 Figure 2 对每个模型展示七个评估轴（主分 + 顺时针 6 维），各轴按 §3.7 的排名指标取值、跨模型 min-max 归一化到 [0,1]（1=最佳、0=最差），并叠加一条中位数虚线多边形；欺诈规避、偿付能力、运营执行、学习四轴做符号翻转，使「离中心越远越好」在每轴一致成立。

当前 Java 移植版缺少：(1) 归一化 + 中位数 + 排名的聚合层；(2) 雷达图渲染；(3) 偿付能力轴所需的 `peak_total_assets`（观察者已跟踪 `peakTotal` 但未落盘）。

目标：新增 `evaluate profile` 子命令，输入多个 session 目录（每 session = 一个模型，含 R 个 run 的 `analysis.json`），输出归一化雷达图 PNG + 跨模型排名表（stdout），并可选导出 CSV/JSON。

## 采用口径（用户已确认：混合）

- **偿付能力**：补落盘 `peak_total_assets`（近零成本），轴值 = `mean(peak_drawdown)/mean(peak_total_assets)`，严格对齐论文 §E.3。
- **学习**：用现有代理 `negotiation_quality.learning_speed.se_half_lift`（越高越好，**不翻转**），**不**改动 opponent 模块补 AnchorRatio/z_anchor。此为对论文的显式偏差，须在输出与代码注释中标注（论文 AnchorRatio 越低越好、需翻转；代理方向相反）。
- 其余五轴均用 `analysis.json` 现有字段。

## 七个轴定义

轴序固定（主分在前，随后顺时针 6 维）。方向用于归一化时的翻转判定。

| # | key | 轴 | 取值（基于 session 内 R 个 run 的均值） | 方向 |
|---|---|---|---|---|
| 0 | `primary` | 主要得分 | `mean(final_balance)` | 越高越好 |
| 1 | `negotiation` | 谈判质量 | `mean(CSE+)` | 越高越好 |
| 2 | `fraud` | 欺诈规避 | `mean(spend_on_bad_supplier_share)` | 越低越好（翻转） |
| 3 | `solvency` | 现金流/偿付 | `mean(peak_drawdown)/mean(peak_total_assets)` | 越低越好（翻转） |
| 4 | `efficiency` | 运营效率 | `(mean(final_balance) − mean(initial_balance))/mean(tool_calls)` | 越高越好 |
| 5 | `execution` | 运营执行 | `mean(controllable_return_rate)` | 越低越好（翻转） |
| 6 | `learning` | 长周期学习 | `mean(se_half_lift)`（代理） | 越高越好 |

派生轴（solvency、efficiency）为比值型：分母为 0 或缺样本时该轴值记为 `null`，归一化时按「缺样本」处理（见下）。

## 归一化与中位数

对每个轴，收集所有模型（session）的轴值 `v_m`：
- 缺样本（`null`）模型在该轴归一化值记为 `null`，雷达图该轴该模型点回退到 0，并在排名表标注 `n/a`。
- 令 `lo=min(v)`、`hi=max(v)`（仅在非 null 值上取）。
- 若 `hi==lo`（含仅一个模型）：所有非 null 模型归一化值取 `1.0`。
- 否则「越高越好」轴：`norm=(v−lo)/(hi−lo)`；「越低越好」轴：`norm=(hi−v)/(hi−lo)`。
- **中位数多边形**：每轴取所有模型归一化值（非 null）的中位数；偶数个取中间两值均值。

不变量：归一化后每轴 **离中心越远越好**（1=该轴最佳、0=最差）。

## 组件与分层

### app 层（唯一底层补齐）
- `CompositeRunObserver`：把已跟踪的 `peakTotal` 传入 `writeAnalysis`。
- `MetricsJsonWriter.writeAnalysis`：新增 `double peakTotalAssets` 参数，在 `profitability` 面板输出 `peak_total_assets`（round2）。

### evaluation 层（新增子系统）
- `ProfileAxis`（enum）：7 轴的 `key`/`label`/`direction`；`Double value(SessionComparison)` 从 `ComparisonReport.SessionComparison` 的 `MetricStat` 均值计算轴值（含 solvency/efficiency 两个派生比值），缺样本返回 `null`。
- `CrossModelProfiler`：`ComparisonReport` → `List<ModelProfile>`（每模型每轴原始值 + 归一化值）+ 每轴中位数；产出 `NormalizedProfile`（不可变记录）。
- `RadarPlotter`：XChart `RadarChart`/`RadarChartBuilder`/`RadarSeries`，每模型一条 series + 一条中位数 series，headless 存 PNG（复用 `BalancePlotter` 的 `java.awt.headless` + `BitmapEncoder.saveBitmap` 剥 `.png` 后缀范式）。中位数 series 若 XChart 不支持虚线，则以独立颜色/图例区分（近似），代码注释标注。
- `RankingTable`：生成 stdout 文本表（每模型每轴原始值 + 归一化值 + 主分排名），并可导出 CSV 与 JSON。
- `RunComparator.METRIC_DEFS`：追加 `peak_total_assets`、`controllable_return_rate`、`initial_balance` 三条，供派生轴计算（其余轴所需 label 已存在）。

### CLI
- `EvaluationCommand` 新增 `profile` 子命令：`--sessions`（`arity=1..*` 会话目录，或位置参数）、`--output <png>`（必填）、`--ranking-output <path>`（可选，按扩展名 .csv/.json 决定格式）、`--title`。成功返回 0，失败打印原因返回 2（与既有子命令一致）。

## 数据流

```
session dirs
  → RunComparator.compare → ComparisonReport（每 session 各 MetricStat 均值/标准差/n）
  → CrossModelProfiler（ProfileAxis 取轴值 → min-max 归一化 + 翻转 + 中位数）→ NormalizedProfile
  → RadarPlotter(PNG) + RankingTable(stdout / CSV / JSON)
```

## 退化与错误处理

- 0 个 session：`profile` 报错返回 2。
- 1 个 session：所有轴归一化为 1.0，中位数=该模型；雷达图仍生成（单多边形 + 重合中位数）。
- 某轴全体缺样本（如所有模型都无谈判 → CSE+ 全 null）：该轴所有模型归一化 null→0，中位数 0；排名表标注 `n/a`。
- PNG 输出父目录不存在：自动 `createDirectories`（同 BalancePlotter）。

## 测试计划（TDD，逐单元红→绿）

- `MetricsJsonWriter` / `CompositeRunObserver`：断言 `profitability.peak_total_assets` 落盘（观察者快照峰值）。
- `RunComparatorTest`：扩展断言 `peak_total_assets`/`controllable_return_rate`/`initial_balance` 三条 metric 的均值。
- `ProfileAxisTest`：7 轴轴值（含 solvency/efficiency 派生比值）、方向标记、缺样本→null。
- `CrossModelProfilerTest`：min-max 归一化、翻转轴方向、`hi==lo` 退化取 1.0、单模型、中位数（奇/偶）、缺样本→null。
- `RadarPlotterTest`：headless 生成非空 PNG 文件、多模型 + 中位数 series。
- `RankingTableTest`：stdout 表含 7 轴与主分排名；CSV/JSON 导出内容与列。
- `EvaluationCommandProfileTest`：`profile` 子命令冒烟（构造临时 session 目录 → 产出 PNG + 排名）。
- 全量 `mvn clean verify`（checkstyle 配置已恢复）：8 模块 BUILD SUCCESS、0 violations、JaCoCo 门禁通过。

## 明确不做

- 不补 AnchorRatio/z_anchor/NewLow（opponent 模块不动）。
- 不做日结 9→13 Processor 对齐。
- 不自动 commit（含本 spec），按 git 安全规范须用户明确授权。

## 记录的偏差

1. 学习轴用 `se_half_lift`（越高越好、不翻转）代理论文 `AnchorRatio`（越低越好、翻转）——方向相反，输出与注释显式标注。
2. 雷达图中位数「虚线」受 XChart `RadarSeries` 线型能力限制，可能以独立颜色/图例近似。
