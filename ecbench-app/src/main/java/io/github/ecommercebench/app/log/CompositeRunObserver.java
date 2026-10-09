package io.github.ecommercebench.app.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecommercebench.agent.RunJob;
import io.github.ecommercebench.agent.RunObserver;
import io.github.ecommercebench.agent.RunResult;
import io.github.ecommercebench.agent.tool.ToolExecutionResult;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.llm.model.ChatMessage;
import io.github.ecommercebench.opponent.metrics.NegotiationMetrics;
import io.github.ecommercebench.simulation.SimulationEngine;
import io.github.ecommercebench.simulation.daily.DailyResult;
import io.github.ecommercebench.simulation.dto.BalanceView;
import io.github.ecommercebench.simulation.state.WarehouseLot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 组合式运行观察者：把 Agent 主循环事件委派给四个兼容写入器（消息 JSONL、余额 CSV、指标 JSON、输出日志）。
 *
 * <p>余额行在 episode 起始（第 0 天）与每批工具执行后按当前日期快照，由 {@link BalanceCsvWriter} 按日期去重，等价于 Python 在
 * daily_trigger 时写行的效果。谈判指标经 {@code Supplier} 惰性获取，使本类与谈判子系统的装配解耦。close 幂等地关闭全部写入器。
 */
public final class CompositeRunObserver implements RunObserver, AutoCloseable {

    private final JsonlMessageWriter messages;
    private final BalanceCsvWriter balance;
    private final MetricsJsonWriter metrics;
    private final OutputLogWriter outputLog;
    private final SimulationEngine engine;
    private final Supplier<NegotiationMetrics> negotiationSupplier;

    // 峰值回撤跟踪：端口 Python _peak_drawdown（逐日 total 净值的最大峰-谷跌幅）
    private double peakTotal = Double.NEGATIVE_INFINITY;
    private double maxDrawdown;

    // 逐工具调用计数：按线格式工具名累加，供运营效率面板（论文 §E.4）聚合为八类活动带
    private final Map<String, Integer> toolCallCounts = new LinkedHashMap<>();

    /** 按 run 目录与索引装配四类写入器（消息/余额/指标/输出），并持有引擎与惰性谈判指标供应器。 */
    public CompositeRunObserver(
            RunDirectory directory,
            int runIndex,
            SimulationEngine engine,
            ObjectMapper mapper,
            Supplier<NegotiationMetrics> negotiationSupplier) {
        this.engine = engine;
        this.negotiationSupplier = negotiationSupplier;
        this.messages = new JsonlMessageWriter(directory.messagesJsonl(runIndex), mapper);
        this.balance = new BalanceCsvWriter(directory.balanceCsv(runIndex));
        this.metrics = new MetricsJsonWriter(directory, runIndex, mapper);
        this.outputLog = new OutputLogWriter(directory.outputLog(runIndex));
    }

    /** episode 起始：落盘 job 的初始消息并快照第 0 天余额。 */
    @Override
    public void onRunStart(RunJob job) {
        for (ChatMessage message : job.initialMessages()) {
            messages.writeMessage(message);
        }
        snapshotBalance();
    }

    @Override
    public void onAssistantMessage(ChatMessage message) {
        messages.writeMessage(message);
    }

    /** 逐条写工具结果 JSONL 并按工具名累加调用计数，记录输出日志后快照当前余额。 */
    @Override
    public void onToolResults(List<ToolExecutionResult> results) {
        for (ToolExecutionResult result : results) {
            messages.writeToolResult(result);
            toolCallCounts.merge(result.toolName(), 1, Integer::sum);
        }
        outputLog.logToolResults(results);
        snapshotBalance();
    }

    @Override
    public void onContextTruncation(int turn, int tokensFreed) {
        messages.writeContextTruncation(turn, tokensFreed);
    }

    /** 收尾：快照余额，取谈判指标并写入，最后连同回撤与工具计数一并写出分析产物。 */
    @Override
    public void onRunComplete(RunResult result) {
        snapshotBalance();
        NegotiationMetrics negotiation = negotiationSupplier.get();
        metrics.writeNegotiationMetrics(negotiation);
        metrics.writeAnalysis(result, engine, negotiation, maxDrawdown, peakTotal, toolCallCounts);
    }

    /** 失败路径：记一行 [FAILURE]，并尽力写出谈判指标（此处指标异常被吞掉，以免掩盖原始异常）。 */
    @Override
    public void onFailure(Throwable error) {
        outputLog.write("[FAILURE] " + error);
        try {
            metrics.writeNegotiationMetrics(negotiationSupplier.get());
        } catch (RuntimeException ignored) {
            // 尽力而为：失败路径下指标缺失不得掩盖原始异常。
        }
    }

    /** 按当前日期快照余额，更新峰值与最大回撤，并写出含门店数、库存件数、仓储费的余额行。 */
    private void snapshotBalance() {
        BalanceView view = engine.checkBalance();
        double total = view.total().amount().doubleValue();
        peakTotal = Math.max(peakTotal, total);
        maxDrawdown = Math.max(maxDrawdown, peakTotal - total);
        int warehouseItems =
                engine.state().warehouse().allLots().stream().mapToInt(WarehouseLot::quantity).sum();
        DailyResult last = engine.lastDailyResult();
        Money storage = last != null ? last.storageCharged() : Money.ZERO;
        balance.writeRow(
                new DailyBalance(
                        view.date(),
                        view.bankBalance(),
                        view.platformWallet(),
                        view.total(),
                        engine.state().openStoreCount(),
                        warehouseItems,
                        storage));
    }

    /** 依次关闭消息/余额/指标/输出四类写入器（各写入器 close 幂等）。 */
    @Override
    public void close() {
        messages.close();
        balance.close();
        metrics.close();
        outputLog.close();
    }
}
