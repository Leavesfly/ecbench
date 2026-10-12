package io.github.ecommercebench.app.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 论文 §3.7 / §4.3 的六个评估维度及其排名指标口径（dashboard 维度视图的唯一定义处）。
 *
 * <p>枚举顺序即论文 §4.3.1–4.3.6 的顺序，前端按此顺序渲染维度卡。每维度给出：排名指标（{@link #raw} 从单个 run 的
 * {@code analysis.json} 取原始值，派生比值在分母为 0 时返回 null）、方向（{@link #lowerIsBetter}）、以及一组按 JSON path
 * 取值的诊断指标（{@link #diagnostics}）。学习维度例外：它的排名指标 AnchorRatio 来自论文 §E.6 的重算结果（逐谈判记录），
 * 不在 {@code analysis.json} 内，故 {@link #raw} 恒为 null、{@link #diagnostics} 为空，其数值由 {@code DimensionService}
 * 从 {@code SessionLearning} 填充。
 */
public enum DimensionAxis {
    NEGOTIATION("negotiation", "谈判质量", "§4.3.1 / §E.1", "CSE+", "已成交剩余效率", "", false),
    FRAUD("fraud", "欺诈规避", "§4.3.2 / §E.2", "BadSpend%", "欺诈支出占比", "", true),
    SOLVENCY("solvency", "现金流与偿付", "§4.3.3 / §E.3", "drawdown / peak assets", "回撤比", "", true),
    EFFICIENCY("efficiency", "运营效率", "§4.3.4 / §E.4", "profit / tool call", "每次工具调用利润", "¥", false),
    EXECUTION("execution", "运营执行", "§4.3.5 / §E.5", "controllable return rate", "可控退货率", "", true),
    LEARNING("learning", "长周期学习", "§4.3.6 / §E.6", "AnchorRatio", "锚定比", "", true);

    /**
     * 一个诊断指标：论文/产物原始名 + 中文注解 + 在 analysis.json 中的取值路径。
     */
    public record Diagnostic(String label, String labelZh, List<String> path) {

        Diagnostic(String label, String labelZh, String... path) {
            this(label, labelZh, List.of(path));
        }
    }

    private final String key;
    private final String labelZh;
    private final String paperRef;
    private final String rankLabel;
    private final String rankLabelZh;
    private final String unit;
    private final boolean lowerIsBetter;

    DimensionAxis(
            String key,
            String labelZh,
            String paperRef,
            String rankLabel,
            String rankLabelZh,
            String unit,
            boolean lowerIsBetter) {
        this.key = key;
        this.labelZh = labelZh;
        this.paperRef = paperRef;
        this.rankLabel = rankLabel;
        this.rankLabelZh = rankLabelZh;
        this.unit = unit;
        this.lowerIsBetter = lowerIsBetter;
    }

    public String key() {
        return key;
    }

    public String labelZh() {
        return labelZh;
    }

    public String paperRef() {
        return paperRef;
    }

    public String rankLabel() {
        return rankLabel;
    }

    public String rankLabelZh() {
        return rankLabelZh;
    }

    public String unit() {
        return unit;
    }

    /** 排名指标是否为「越低越好」（决定 rank 1 的方向）。 */
    public boolean lowerIsBetter() {
        return lowerIsBetter;
    }

    /** 该维度排名指标在单个 run 上的原始值；缺字段或分母为 0 时返回 null。学习维度恒为 null。 */
    public Double raw(JsonNode analysis) {
        return switch (this) {
            case NEGOTIATION -> at(analysis, "negotiation_quality", "CSE+");
            case FRAUD -> at(analysis, "fraud_identification", "spend_on_bad_supplier_share");
            case SOLVENCY -> ratio(
                    at(analysis, "profitability", "peak_drawdown"),
                    at(analysis, "profitability", "peak_total_assets"));
            case EFFICIENCY -> ratio(
                    difference(
                            at(analysis, "profitability", "final_balance"),
                            at(analysis, "profitability", "initial_balance")),
                    at(analysis, "operational_efficiency", "total_tool_calls"));
            case EXECUTION -> at(analysis, "return_management", "controllable_return_rate");
            case LEARNING -> null;
        };
    }

    /** 该维度的诊断指标清单（学习维度为空，其数值由 SessionLearning 提供）。 */
    public List<Diagnostic> diagnostics() {
        return switch (this) {
            case NEGOTIATION -> List.of(
                    new Diagnostic("SE+", "剩余效率", "negotiation_quality", "SE+"),
                    new Diagnostic("%Oracle", "预言机归一化剩余", "negotiation_quality", "%Oracle"),
                    new Diagnostic("AGR+", "诚信供应商成交率", "negotiation_quality", "AGR+"),
                    new Diagnostic("avg_rounds", "达成交易所需轮次",
                            "negotiation_quality", "avg_rounds_to_deal"),
                    new Diagnostic("money_saved", "相对初始报价的节省",
                            "negotiation_quality", "total_money_saved_vs_initial"));
            case FRAUD -> List.of(
                    new Diagnostic("bad_order_share", "欺诈供应商订单占比",
                            "fraud_identification", "bad_supplier_order_share"),
                    new Diagnostic("spend_on_bad", "欺诈供应商支出",
                            "fraud_identification", "spend_on_bad_supplier"),
                    new Diagnostic("vip_fee_paid_count", "已支付会员费次数",
                            "fraud_identification", "vip_fee_paid_count"),
                    new Diagnostic("vip_fee_paid_amount", "已支付会员费金额",
                            "fraud_identification", "vip_fee_paid_amount"),
                    new Diagnostic("contacted_bad", "接触过的欺诈供应商数",
                            "supplier_engagement", "contacted", "distinct_bad"),
                    new Diagnostic("ordered_bad", "下过单的欺诈供应商数",
                            "supplier_engagement", "ordered", "distinct_bad"));
            case SOLVENCY -> List.of(
                    new Diagnostic("peak_drawdown", "峰值回撤", "profitability", "peak_drawdown"),
                    new Diagnostic("peak_total_assets", "峰值总资产",
                            "profitability", "peak_total_assets"),
                    new Diagnostic("bankrupt", "是否破产", "profitability", "bankrupt"),
                    new Diagnostic("final_day", "结束日", "profitability", "final_day"));
            case EFFICIENCY -> List.of(
                    new Diagnostic("profit_per_tool_call", "每次工具调用利润",
                            "operational_efficiency", "profit_per_tool_call"),
                    new Diagnostic("tool_calls", "工具调用数",
                            "operational_efficiency", "total_tool_calls"),
                    new Diagnostic("turns", "交互轮次", "operational_efficiency", "turns"),
                    new Diagnostic("context_evictions", "上下文驱逐次数",
                            "operational_efficiency", "context_evictions"),
                    new Diagnostic("memory_calls", "记忆访问次数",
                            "operational_efficiency", "memory_calls"));
            case EXECUTION -> List.of(
                    new Diagnostic("controllable_return_rate", "可控退货率",
                            "return_management", "controllable_return_rate"),
                    new Diagnostic("realized_return_rate", "实际退货率",
                            "return_management", "realized_return_rate"),
                    new Diagnostic("on_time_ship", "准时发货率",
                            "fulfilment_quality", "on_time_ship_rate"),
                    new Diagnostic("refund_loss_total", "退款损失",
                            "return_management", "refund_loss_total"),
                    new Diagnostic("shipping_loss_on_returns", "退货运费损失",
                            "return_management", "shipping_loss_on_returns"));
            case LEARNING -> List.of();
        };
    }

    /** 按 path 取数值：缺失、null 或非数值 → null；布尔按 1.0/0.0 计（与 RunComparator 一致）。 */
    public static Double at(JsonNode root, String... path) {
        JsonNode current = root;
        for (String key : path) {
            if (current == null) {
                return null;
            }
            current = current.get(key);
        }
        if (current == null || current.isNull()) {
            return null;
        }
        if (current.isNumber()) {
            return current.asDouble();
        }
        return current.isBoolean() ? (current.booleanValue() ? 1.0 : 0.0) : null;
    }

    private static Double difference(Double minuend, Double subtrahend) {
        return minuend == null || subtrahend == null ? null : minuend - subtrahend;
    }

    private static Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0.0) {
            return null;
        }
        return numerator / denominator;
    }
}
