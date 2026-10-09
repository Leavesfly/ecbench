package io.github.ecommercebench.opponent.metrics;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.RandomStreams;
import io.github.ecommercebench.opponent.model.NegotiationRecord;
import io.github.ecommercebench.opponent.model.SupplierFamily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录全部谈判并计算 TERMS Bench 聚合指标。
 */
public final class NegotiationTracker {

    private final Map<String, NegotiationRecord> active = new LinkedHashMap<>();
    private final List<NegotiationRecord> completed = new ArrayList<>();
    private final RandomStreams randomStreams;

    public NegotiationTracker(RandomStreams randomStreams) {
        this.randomStreams = randomStreams;
    }

    public NegotiationRecord getOrCreate(
            String supplierName,
            String skuId,
            String supplierType,
            SupplierFamily family,
            Money referencePrice,
            Money costFloor,
            Money initialOffer,
            int dayStarted) {
        return active.computeIfAbsent(
                key(supplierName, skuId),
                ignored ->
                        new NegotiationRecord(
                                supplierName,
                                skuId,
                                supplierType,
                                family,
                                referencePrice,
                                costFloor,
                                initialOffer,
                                dayStarted));
    }

    public void recordAgentOffer(String supplier, String sku, Money price) {
        NegotiationRecord record = active.get(key(supplier, sku));
        if (record != null) {
            record.recordAgentOffer(price);
        }
    }

    public void recordSupplierOffer(String supplier, String sku, Money price) {
        NegotiationRecord record = active.get(key(supplier, sku));
        if (record != null) {
            record.recordSupplierOffer(price);
        }
    }

    public void recordOutcome(
            String supplier, String sku, String outcome, Money price, String terminatedBy, int day) {
        NegotiationRecord record = active.remove(key(supplier, sku));
        if (record != null) {
            record.complete(outcome, price, terminatedBy, day);
            completed.add(record);
        }
    }

    public List<NegotiationRecord> completed() {
        return List.copyOf(completed);
    }

    public NegotiationMetrics aggregate() {
        List<NegotiationRecord> good =
                completed.stream().filter(r -> "good".equals(r.supplierType())).toList();
        List<NegotiationRecord> bad =
                completed.stream().filter(r -> "bad".equals(r.supplierType())).toList();
        long goodDeals = good.stream().filter(this::isAgreement).count();
        long badDeals = bad.stream().filter(this::isAgreement).count();
        Double agr = good.isEmpty() ? null : (double) goodDeals / good.size();
        Double fagr = bad.isEmpty() ? null : (double) badDeals / bad.size();
        List<Double> efficiencies = good.stream().map(this::surplusEfficiency).toList();
        List<Double> agreedEfficiencies =
                good.stream().filter(this::isAgreement).map(this::surplusEfficiency).toList();
        Double se = good.isEmpty() ? null : average(efficiencies);
        Double cse = agreedEfficiencies.isEmpty() ? null : average(agreedEfficiencies);
        long violations = completed.stream().filter(r -> !r.criticalViolations().isEmpty()).count();
        Double violationRate = completed.isEmpty() ? null : (double) violations / completed.size();
        List<NegotiationRecord> deals = completed.stream().filter(this::isAgreement).toList();
        double avgRounds =
                deals.isEmpty()
                        ? 0.0
                        : deals.stream().mapToInt(NegotiationRecord::rounds).average().orElse(0.0);
        double totalSaved =
                deals.stream()
                        .mapToDouble(
                                r -> r.initialOffer().amount().subtract(r.finalPrice().amount()).doubleValue())
                        .sum();
        return new NegotiationMetrics(
                completed.size(),
                agr,
                fagr,
                se,
                cse,
                cse,
                violationRate,
                round2(avgRounds),
                round2(totalSaved),
                learningMetrics(good),
                anchoringMetrics(good));
    }

    private Map<String, Object> learningMetrics(List<NegotiationRecord> good) {
        List<NegotiationRecord> timed = good.stream().filter(r -> r.dayConcluded() != null).toList();
        if (timed.size() < 4) {
            return Map.of();
        }
        int middle = timed.size() / 2;
        double early = average(timed.subList(0, middle).stream().map(this::surplusEfficiency).toList());
        double late =
                average(timed.subList(middle, timed.size()).stream().map(this::surplusEfficiency).toList());
        return Map.of("se_half_lift", round4(late - early), "good_negotiations", timed.size());
    }

    /**
     * 计算重复采购相对历史最低价的后悔值；排列检验随机流保留给完整统计扩展。
     */
    private Map<String, Object> anchoringMetrics(List<NegotiationRecord> good) {
        Map<String, List<NegotiationRecord>> grouped = new LinkedHashMap<>();
        good.stream()
                .filter(this::isAgreement)
                .forEach(
                        record ->
                                grouped
                                        .computeIfAbsent(
                                                key(record.supplierName(), record.skuId()), ignored -> new ArrayList<>())
                                        .add(record));
        List<Double> regrets = new ArrayList<>();
        for (List<NegotiationRecord> records : grouped.values()) {
            if (records.size() < 2) {
                continue;
            }
            records.sort(java.util.Comparator.comparing(NegotiationRecord::dayConcluded));
            double best = normalizedPrice(records.get(0));
            for (int index = 1; index < records.size(); index++) {
                double current = normalizedPrice(records.get(index));
                regrets.add(Math.max(0.0, current - best));
                best = Math.min(best, current);
            }
        }
        randomStreams.stream("anchoring-permutation");
        return regrets.isEmpty()
                ? Map.of()
                : Map.of("anchor_regret", round4(average(regrets)), "events", regrets.size());
    }

    private boolean isAgreement(NegotiationRecord record) {
        return "Agreement".equals(record.outcome()) && record.finalPrice() != null;
    }

    private double surplusEfficiency(NegotiationRecord record) {
        if (!isAgreement(record)) {
            return 0.0;
        }
        double span =
                record.referencePrice().amount().subtract(record.costFloor().amount()).doubleValue();
        return span <= 0
                ? 0.0
                : record.referencePrice().amount().subtract(record.finalPrice().amount()).doubleValue()
                / span;
    }

    private double normalizedPrice(NegotiationRecord record) {
        double floor = record.costFloor().amount().doubleValue();
        double span = record.referencePrice().amount().doubleValue() - floor;
        return span <= 0
                ? 0.0
                : Math.max(0.0, Math.min(1.0, (record.finalPrice().amount().doubleValue() - floor) / span));
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String key(String supplier, String sku) {
        return supplier + "\u0000" + sku;
    }
}
