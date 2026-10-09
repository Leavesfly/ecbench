package io.github.ecommercebench.app.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ecommercebench.agent.RunResult;
import io.github.ecommercebench.agent.TerminationReason;
import io.github.ecommercebench.domain.catalog.Supplier;
import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.opponent.metrics.NegotiationMetrics;
import io.github.ecommercebench.opponent.model.FraudType;
import io.github.ecommercebench.simulation.SimulationEngine;
import io.github.ecommercebench.simulation.stats.FraudStats;
import io.github.ecommercebench.simulation.stats.FulfilmentStats;
import io.github.ecommercebench.simulation.stats.ReturnStats;
import io.github.ecommercebench.simulation.stats.SupplierEngagement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 指标 JSON 写入器，端口 Python {@code _save_negotiation_metrics}/{@code _save_analysis_report}。
 *
 * <p>写出 {@code run_{idx}_negotiation_metrics.json}（TERMS 聚合，样本不足的指标为 JSON null）与 {@code
 * run_{idx}_analysis.json}（reward/profitability/negotiation_quality/fulfilment_quality/return_management
 * 面板， 尚未在仿真层跟踪的指标为 JSON null）。使用 indent=2 的美化输出、非 ASCII 原样，与 Python {@code json.dump} 一致。close 幂等。
 */
public final class MetricsJsonWriter implements AutoCloseable {

    private final RunDirectory directory;
    private final int runIndex;
    private final ObjectMapper mapper;

    /** 绑定 run 目录、run 索引与 JSON mapper，用于按索引命名写出指标文件。 */
    public MetricsJsonWriter(RunDirectory directory, int runIndex, ObjectMapper mapper) {
        this.directory = directory;
        this.runIndex = runIndex;
        this.mapper = mapper;
    }

    /** 写出 TERMS 聚合指标 JSON；样本不足（值为 null）的指标以 JSON null 落盘。 */
    public void writeNegotiationMetrics(NegotiationMetrics metrics) {
        ObjectNode root = mapper.createObjectNode();
        root.put("total_negotiations", metrics.completedNegotiations());
        ObjectNode terms = mapper.createObjectNode();
        putDouble(terms, "SE+", metrics.sePlus());
        putDouble(terms, "AGR+", metrics.agrPlus());
        putDouble(terms, "CSE+", metrics.csePlus());
        putDouble(terms, "FAGR-", metrics.fagrMinus());
        putDouble(terms, "CritViol", metrics.criticalViolationRate());
        putDouble(terms, "%Oracle", metrics.percentOracle());
        root.set("terms_bench_metrics", terms);
        root.set("learning_speed", mapper.valueToTree(metrics.learning()));
        root.set("anchoring", mapper.valueToTree(metrics.anchoring()));
        write(directory.negotiationMetricsJson(runIndex), root);
    }

    /**
     * 写出 run 分析面板 JSON：reward/profitability/negotiation_quality/fulfilment_quality/return_management
     * 及欺诈识别/供应商触达/运营效率各段，未在仿真层跟踪的指标置 JSON null。
     */
    public void writeAnalysis(
            RunResult result,
            SimulationEngine engine,
            NegotiationMetrics negotiation,
            double peakDrawdown,
            double peakTotalAssets,
            Map<String, Integer> toolCallCounts) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode reward = mapper.createObjectNode();
        double initial = engine.config().initialBalance().amount().doubleValue();
        if (initial == 0.0) {
            reward.putNull("final_score");
        } else {
            reward.put("final_score", result.finalTotalBalance() / initial);
        }
        reward.put("termination_reason", result.terminationReason().canonical());
        if (result.terminationDetail() == null) {
            reward.putNull("termination_detail");
        } else {
            reward.put("termination_detail", result.terminationDetail());
        }
        root.set("reward", reward);

        ObjectNode profitability = mapper.createObjectNode();
        profitability.put("final_balance", result.finalTotalBalance());
        profitability.put("initial_balance", initial);
        profitability.put("bankrupt", result.terminationReason() == TerminationReason.BANKRUPT);
        profitability.put("final_day", result.finalDay());
        profitability.put("stores_opened", engine.state().stores().size());
        profitability.put("store_reopens", storeReopens(engine));
        profitability.put("peak_drawdown", round2(peakDrawdown));
        profitability.put("peak_total_assets", round2(peakTotalAssets));
        root.set("profitability", profitability);

        ObjectNode negotiationQuality = mapper.createObjectNode();
        putDouble(negotiationQuality, "SE+", negotiation.sePlus());
        putDouble(negotiationQuality, "CSE+", negotiation.csePlus());
        putDouble(negotiationQuality, "%Oracle", negotiation.percentOracle());
        putDouble(negotiationQuality, "AGR+", negotiation.agrPlus());
        negotiationQuality.put("avg_rounds_to_deal", negotiation.avgRoundsToDeal());
        negotiationQuality.put("total_money_saved_vs_initial", negotiation.totalMoneySavedVsInitial());
        negotiationQuality.set("learning_speed", mapper.valueToTree(negotiation.learning()));
        root.set("negotiation_quality", negotiationQuality);

        FulfilmentStats fulfilment = engine.state().fulfilmentStats();
        ReturnStats returns = engine.state().returnStats();
        int unitsSold = fulfilment.unitsSold();
        int unitsReturned = returns.unitsReturned();
        int ordersSold = fulfilment.ordersSold();
        double realizedReturnRate = round4((double) unitsReturned / Math.max(1, unitsSold));

        ObjectNode fulfilmentQuality = mapper.createObjectNode();
        fulfilmentQuality.put("orders_sold", ordersSold);
        fulfilmentQuality.put("orders_shipped", fulfilment.ordersShipped());
        fulfilmentQuality.put("orders_cancelled", fulfilment.ordersCancelled());
        fulfilmentQuality.put(
                "on_time_ship_rate", round4((double) fulfilment.ordersShipped() / Math.max(1, ordersSold)));
        fulfilmentQuality.put("units_sold", unitsSold);
        fulfilmentQuality.put("units_returned", unitsReturned);
        fulfilmentQuality.put("realized_return_rate", realizedReturnRate);
        fulfilmentQuality.set("ship_speed_counts", shipSpeedCounts(fulfilment));
        root.set("fulfilment_quality", fulfilmentQuality);

        ObjectNode returnManagement = mapper.createObjectNode();
        returnManagement.put("units_sold", unitsSold);
        returnManagement.put("units_returned", unitsReturned);
        returnManagement.put("realized_return_rate", realizedReturnRate);
        returnManagement.put(
                "refund_loss_total", round2(returns.refundLossTotal().amount().doubleValue()));
        returnManagement.put(
                "shipping_loss_on_returns", round2(returns.shippingLossOnReturns().amount().doubleValue()));
        int unitsShipped = returns.unitsShipped();
        double shippedDenominator = Math.max(1, unitsShipped);
        returnManagement.put("units_shipped", unitsShipped);
        returnManagement.put("exp_return_rate", round4(returns.expReturnsTotal() / shippedDenominator));
        returnManagement.put(
                "exp_return_rate_natural", round4(returns.expReturnsNatural() / shippedDenominator));
        returnManagement.put(
                "exp_return_rate_from_pricing", round4(returns.expReturnsPrice() / shippedDenominator));
        returnManagement.put(
                "exp_return_rate_from_ship_speed",
                round4(returns.expReturnsShipSpeed() / shippedDenominator));
        returnManagement.put(
                "exp_return_rate_from_defective_fraud",
                round4(returns.expReturnsDefective() / shippedDenominator));
        returnManagement.put(
                "controllable_return_rate",
                round4((returns.expReturnsPrice() + returns.expReturnsShipSpeed()) / shippedDenominator));
        returnManagement.set("ship_speed_counts", shipSpeedCounts(fulfilment));
        root.set("return_management", returnManagement);

        root.set("fraud_identification", fraudIdentification(engine));
        root.set("supplier_engagement", supplierEngagement(engine));
        root.set("operational_efficiency", operationalEfficiency(result, initial, toolCallCounts));

        write(directory.analysisJson(runIndex), root);
    }

    /**
     * 欺诈识别面板，端口 Python get_analysis_report 的 fraud_identification（单点记账来自 OrderProcessor）。
     */
    private ObjectNode fraudIdentification(SimulationEngine engine) {
        FraudStats fraud = engine.state().fraudStats();
        int ordersTotal = fraud.ordersTotal();
        int ordersFromBad = fraud.ordersFromBadSupplier();
        double spendTotal = fraud.spendTotal().amount().doubleValue();
        double spendOnBad = fraud.spendOnBadSupplier().amount().doubleValue();
        long badSuppliersTotal =
                engine.catalog().suppliers().stream().filter(s -> "bad".equals(s.supplierType())).count();

        ObjectNode panel = mapper.createObjectNode();
        panel.put("bad_suppliers_total", badSuppliersTotal);
        panel.put("orders_total", ordersTotal);
        panel.put("orders_from_bad_supplier", ordersFromBad);
        panel.put(
                "bad_supplier_order_share", round4((double) ordersFromBad / Math.max(1, ordersTotal)));
        panel.put("spend_total", round2(spendTotal));
        panel.put("spend_on_bad_supplier", round2(spendOnBad));
        panel.put("spend_on_bad_supplier_share", round4(spendOnBad / Math.max(1e-9, spendTotal)));
        panel.put("vip_fee_paid_count", fraud.vipFeePaidCount());
        panel.put("vip_fee_paid_amount", round2(fraud.vipFeePaidAmount().amount().doubleValue()));

        ObjectNode byType = mapper.createObjectNode();
        Set<String> types = new TreeSet<>();
        // 预置全部 5 个欺诈型（同 Python per_type 初始化），未记录的置 0
        for (FraudType fraudType : FraudType.values()) {
            if (fraudType != FraudType.NONE) {
                types.add(fraudType.wireName());
            }
        }
        types.addAll(fraud.perType().keySet());
        types.addAll(fraud.perTypeOrders().keySet());
        types.addAll(fraud.perTypeUnits().keySet());
        for (String type : types) {
            ObjectNode bucket = mapper.createObjectNode();
            bucket.put("orders", fraud.perTypeOrders().getOrDefault(type, 0));
            bucket.put("units", fraud.perTypeUnits().getOrDefault(type, 0));
            Money spend = fraud.perType().get(type);
            bucket.put("spend", spend == null ? 0.0 : round2(spend.amount().doubleValue()));
            byType.set(type, bucket);
        }
        panel.set("spend_by_fraud_type", byType);

        Set<String> personalities = new TreeSet<>();
        engine.catalog().suppliers().stream()
                .filter(supplier -> "good".equals(supplier.supplierType()))
                .map(Supplier::personality)
                .filter(personality -> personality != null && !personality.isBlank())
                .forEach(personalities::add);
        ObjectNode byPersonality = mapper.createObjectNode();
        // 预置全部好供应商人格（同 Python spend_by_personality 从名册初始化），未记录的置 0.0
        for (String personality : personalities) {
            byPersonality.put(personality, 0.0);
        }
        fraud
                .spendByPersonality()
                .forEach((p, m) -> byPersonality.put(p, round2(m.amount().doubleValue())));
        panel.set("spend_by_good_personality", byPersonality);
        return panel;
    }

    /**
     * 供应商触达面板，端口 Python _supplier_engagement_report：contacted/ordered/roster_totals 各按类型分桶。
     */
    private ObjectNode supplierEngagement(SimulationEngine engine) {
        Map<String, Supplier> byName = new LinkedHashMap<>();
        engine.catalog().suppliers().forEach(supplier -> byName.put(supplier.supplierName(), supplier));
        SupplierEngagement engagement = engine.state().supplierEngagement();
        ObjectNode panel = mapper.createObjectNode();
        panel.set("contacted", engagementBucket(engagement.contacted(), byName));
        panel.set("ordered", engagementBucket(engagement.ordered(), byName));
        panel.set("roster_totals", engagementBucket(byName.keySet(), byName));
        return panel;
    }

    /** 把一组供应商名按 good/bad 分桶计数，并进一步按人格/欺诈类型细分。 */
    private ObjectNode engagementBucket(Collection<String> names, Map<String, Supplier> byName) {
        int good = 0;
        int bad = 0;
        ObjectNode goodByPersonality = mapper.createObjectNode();
        ObjectNode badByFraudType = mapper.createObjectNode();
        for (String name : names) {
            Supplier supplier = byName.get(name);
            if (supplier == null) {
                continue;
            }
            if ("good".equals(supplier.supplierType())) {
                good++;
                String personality =
                        supplier.personality() == null || supplier.personality().isBlank()
                                ? "Unknown"
                                : supplier.personality();
                goodByPersonality.put(personality, goodByPersonality.path(personality).asInt(0) + 1);
            } else if ("bad".equals(supplier.supplierType())) {
                bad++;
                String fraudType =
                        supplier.fraudType() == null || supplier.fraudType().isBlank()
                                ? "unknown"
                                : supplier.fraudType();
                badByFraudType.put(fraudType, badByFraudType.path(fraudType).asInt(0) + 1);
            }
        }
        ObjectNode bucket = mapper.createObjectNode();
        bucket.put("distinct_total", good + bad);
        bucket.put("distinct_good", good);
        bucket.put("distinct_bad", bad);
        bucket.set("good_by_personality", goodByPersonality);
        bucket.set("bad_by_fraud_type", badByFraudType);
        return bucket;
    }

    /**
     * 运营效率面板，端口论文 §E.4：每次工具调用利润、工具调用/轮次、八类活动带计数、上下文驱逐与记忆使用。
     *
     * <p>{@code profit_per_tool_call = (年末总资产 − 初始资本) / 工具调用数}；调用数为 0 时该比率与 {@code memory_share} 为
     * JSON null。 逐工具计数由 {@link ActivityBands} 按固定带序聚合，缺失的带补 0。
     */
    private ObjectNode operationalEfficiency(
            RunResult result, double initial, Map<String, Integer> toolCallCounts) {
        Map<String, Integer> counts = toolCallCounts == null ? Map.of() : toolCallCounts;
        int totalCalls = ActivityBands.total(counts);
        Map<String, Integer> bands = ActivityBands.bandCounts(counts);

        ObjectNode panel = mapper.createObjectNode();
        panel.put("total_tool_calls", totalCalls);
        panel.put("turns", result.turns());
        if (totalCalls == 0) {
            panel.putNull("profit_per_tool_call");
        } else {
            panel.put(
                    "profit_per_tool_call", round2((result.finalTotalBalance() - initial) / totalCalls));
        }

        ObjectNode bandNode = mapper.createObjectNode();
        bands.forEach(bandNode::put);
        panel.set("calls_by_band", bandNode);

        // 各活动带占总调用数的百分比（论文 Table 10）；无调用时退化为 0.0，保持数值型与形状稳定。
        ObjectNode shareNode = mapper.createObjectNode();
        bands.forEach(
                (band, count) ->
                        shareNode.put(band, totalCalls == 0 ? 0.0 : round2(100.0 * count / totalCalls)));
        panel.set("calls_by_band_share", shareNode);

        int memoryCalls = bands.getOrDefault("memory", 0);
        panel.put("memory_calls", memoryCalls);
        if (totalCalls == 0) {
            panel.putNull("memory_share");
        } else {
            panel.put("memory_share", round4((double) memoryCalls / totalCalls));
        }

        panel.put("context_evictions", result.contextClearCount());
        panel.put("context_tokens_freed", result.contextTokensFreedTotal());
        return panel;
    }

    /** 写入浮点字段：值为 null 时写 JSON null（而非省略字段），保持结构稳定。 */
    private void putDouble(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /** 输出发货速度计数，预置 fast/standard/slow 三键（缺省 0）再按实际覆盖。 */
    private ObjectNode shipSpeedCounts(FulfilmentStats fulfilment) {
        ObjectNode node = mapper.createObjectNode();
        node.put("fast", 0);
        node.put("standard", 0);
        node.put("slow", 0);
        fulfilment.shipSpeedCounts().forEach((speed, count) -> node.put(speed.wireName(), count));
        return node;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** 复开店次数 = 各店型“开启次数−1”之和（首开不计，重复开同一店型计为重开）。 */
    private static int storeReopens(SimulationEngine engine) {
        int reopens = 0;
        for (int count : engine.state().storeTypeOpenCounts().values()) {
            reopens += Math.max(0, count - 1);
        }
        return reopens;
    }

    /** 以 indent=2 美化 JSON 写盘（非 ASCII 原样），失败包装为 UncheckedIOException。 */
    private void write(Path file, ObjectNode root) {
        try {
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException exception) {
            throw new UncheckedIOException("写入指标文件失败: " + file, exception);
        }
    }

    /** 无长持资源需释放（指标文件均为一次性写出），仅满足 AutoCloseable 契约。 */
    @Override
    public void close() {
        // 指标文件按需一次性写出，无长持流需关闭。
    }
}
