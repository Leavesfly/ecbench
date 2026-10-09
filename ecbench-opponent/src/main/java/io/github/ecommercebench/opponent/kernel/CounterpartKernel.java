package io.github.ecommercebench.opponent.kernel;

import io.github.ecommercebench.domain.money.Money;
import io.github.ecommercebench.domain.random.DeterministicRandom;
import io.github.ecommercebench.opponent.model.CounterpartAction;
import io.github.ecommercebench.opponent.model.NegotiationDecision;
import io.github.ecommercebench.opponent.model.SupplierFamily;

import java.util.ArrayList;
import java.util.List;

/**
 * TERMS Bench 供应商策略的确定性 Java 端口。
 */
public final class CounterpartKernel {

    private final KernelParameters parameters;
    private final DeterministicRandom random;
    private final Preset preset;
    private final List<Double> agentHistory = new ArrayList<>();
    private final List<Double> supplierHistory = new ArrayList<>();
    private Double lastSupplierPrice;

    /** 以参数与随机流建立内核；Preset 由供应商家族(family)与立场(stance)共决。 */
    public CounterpartKernel(KernelParameters parameters, DeterministicRandom random) {
        this.parameters = parameters;
        this.random = random;
        this.preset = Preset.forFamily(parameters.family(), parameters.stance());
    }

    /**
     * 供应该对手本回合的动作：若 Agent 已报价则先判定接受/拒绝，否则（或继续谈判时）给出一个新的还价。
     *
     * <p>还价基于首轮开盘价或逐轮让步价，并保证不高于 Agent 报价（避免越还越高），最后限幅在保留价与上限之间。
     */
    public CounterpartAction getAction(int round, Money agentPrice) {
        Double offered = agentPrice == null ? null : agentPrice.amount().doubleValue();
        if (offered != null) {
            agentHistory.add(offered);
            NegotiationDecision decision = decideResponse(round, offered);
            if (decision == NegotiationDecision.ACCEPT) {
                return new CounterpartAction(decision, null, "Concede", sentimentCue());
            }
            if (decision == NegotiationDecision.REJECT) {
                return new CounterpartAction(decision, null, "Pressure", sentimentCue());
            }
        }

        double price = lastSupplierPrice == null ? openingOffer() : concessionOffer(round);
        if (offered != null && price < offered) {
            price = offered + 0.03 * range();
            if (lastSupplierPrice != null) {
                price = Math.min(price, lastSupplierPrice);
            }
        }
        price = clamp(price, reservation(), maximum());
        String strategicCue = strategicCue(round, price);
        lastSupplierPrice = price;
        supplierHistory.add(price);
        return new CounterpartAction(
                NegotiationDecision.OFFER, Money.of(price), strategicCue, sentimentCue());
    }

    /**
     * 对 Agent 报价做接受/拒绝/继续的概率判定。
     *
     * <p>favourability 为正（优于保留价）时按“越有利、越接近截止、对方让步越快越僵硬”的组合打分，经 sigmoid 抽接受；
     * 超过半数回合且 favourability 为负时，越不合越接近截止越可能直接拒绝。未中则继续还价。
     */
    private NegotiationDecision decideResponse(int round, double price) {
        double favourability = (price - reservation()) / range();
        if (favourability >= 0) {
            // deadline 随回合递增（越接近上限越急着成交），与历史 speed/rigidity 特征加权成接受得分。
            double deadline = 1.0 - Math.sqrt((double) round / parameters.maxRounds());
            double score =
                    6.0 * favourability
                            + parameters.concessionWillingness()
                            - 2.0 * deadline
                            + preset.rho() * historyFeature("speed")
                            + preset.xi() * historyFeature("rigidity");
            if (random.nextDouble() < sigmoid(score)) {
                return NegotiationDecision.ACCEPT;
            }
        }
        int walkRound = (int) Math.ceil(parameters.maxRounds() / 2.0);
        if (round >= walkRound && favourability < 0) {
            double progress =
                    parameters.maxRounds() > walkRound
                            ? (double) (round - walkRound) / (parameters.maxRounds() - walkRound)
                            : 1.0;
            double score = -4.5 + 30.0 * -favourability + 1.5 * progress;
            if (random.nextDouble() < sigmoid(score)) {
                return NegotiationDecision.REJECT;
            }
        }
        return NegotiationDecision.OFFER;
    }

    /** 首次报价：开盘越 harsh、越 aggressive 越高，叠加微小噪声后限幅到 [保留价, 上限]。 */
    private double openingOffer() {
        double phi =
                clamp(
                        1.0
                                - 0.3 * parameters.concessionWillingness()
                                + ("aggressive".equals(parameters.stance()) ? 0.15 : 0.0)
                                - ("conciliatory".equals(parameters.stance()) ? 0.15 : 0.0),
                        0.5,
                        1.5);
        double opening =
                reservation()
                        + parameters.openingHarshness() * phi * (maximum() - reservation())
                        + random.nextGaussian() * 0.02 * range();
        return clamp(opening, reservation(), maximum());
    }

    /** 逐轮让步：让步幅度受 willingness 与历史 magnitude 影响，按家族施加不同高斯噪声，且不低于保留价、不高于上一轮。 */
    private double concessionOffer(int round) {
        double concession =
                0.12
                        + 0.28 * parameters.concessionWillingness()
                        - preset.lambda2() * historyFeature("magnitude")
                        - ("aggressive".equals(parameters.stance()) ? 0.10 : 0.0)
                        + ("conciliatory".equals(parameters.stance()) ? 0.10 : 0.0);
        concession = clamp(concession, 0.0, 1.0);
        double sigma =
                switch (parameters.family()) {
                    case STOCHASTIC -> 0.08;
                    case EXPRESSIVE, STRATEGIC -> 0.03;
                    default -> 0.01;
                };
        double candidate =
                lastSupplierPrice
                        - concession * (lastSupplierPrice - reservation())
                        + random.nextGaussian() * sigma * range();
        return Math.min(lastSupplierPrice, Math.max(reservation(), candidate));
    }

    /** 基于最近至多 3 个报价增量特征：magnitude=平均正向让步，speed=平均让步，rigidity=最后一次几乎未动。 */
    private double historyFeature(String feature) {
        if (agentHistory.size() < 2) {
            return 0.0;
        }
        List<Double> deltas = new ArrayList<>();
        for (int index = Math.max(1, agentHistory.size() - 3); index < agentHistory.size(); index++) {
            deltas.add(agentHistory.get(index) - agentHistory.get(index - 1));
        }
        if ("magnitude".equals(feature)) {
            return deltas.stream()
                    .mapToDouble(delta -> Math.max(0.0, delta / range()))
                    .average()
                    .orElse(0.0);
        }
        if ("speed".equals(feature)) {
            return deltas.stream().mapToDouble(delta -> delta / range()).average().orElse(0.0);
        }
        if ("rigidity".equals(feature)) {
            return Math.max(0.0, deltas.get(deltas.size() - 1) / range()) < 0.10 ? 1.0 : 0.0;
        }
        return 0.0;
    }

    /** 选择策略提示词（Concede/Hold/Pressure）：寡言/策略型固定 Hold，对抗型固定 Pressure，其余按幅度/截止/立场做 softmax 抽样。 */
    private String strategicCue(int round, double price) {
        if (parameters.family() == SupplierFamily.TACITURN
                || parameters.family() == SupplierFamily.STRATEGIC) {
            return "Hold";
        }
        if (parameters.family() == SupplierFamily.ADVERSARIAL) {
            return "Pressure";
        }
        double magnitude =
                lastSupplierPrice == null
                        ? 0.0
                        : Math.min(
                        1.0,
                        Math.abs(price - lastSupplierPrice)
                                / (Math.abs(lastSupplierPrice - reservation()) + 1e-5));
        double deadline = Math.sqrt((double) round / parameters.maxRounds());
        double[] bias =
                switch (parameters.stance()) {
                    case "conciliatory" -> new double[]{1.0, 0.0, -1.0};
                    case "aggressive" -> new double[]{-1.0, 0.0, 1.0};
                    default -> new double[]{0.0, 0.5, 0.0};
                };
        double[] logits = {
                bias[0] + 2.0 * (magnitude - 0.10), bias[1], bias[2] + 2.0 * (deadline - 0.80) - magnitude
        };
        double temperature = parameters.family() == SupplierFamily.STOCHASTIC ? 2.5 : 1.0;
        double max = Math.max(logits[0], Math.max(logits[1], logits[2]));
        double[] weights = new double[3];
        double total = 0;
        for (int index = 0; index < 3; index++) {
            weights[index] = Math.exp(logits[index] / temperature - max);
            total += weights[index];
        }
        double draw = random.nextDouble() * total;
        return draw < weights[0] ? "Concede" : draw < weights[0] + weights[1] ? "Hold" : "Pressure";
    }

    /** 抽取情绪提示（positive/neutral/negative）：寡言/策略型中性，对抗型负面，其余按立场均值与家族方差抽正态后分档。 */
    private String sentimentCue() {
        if (parameters.family() == SupplierFamily.TACITURN
                || parameters.family() == SupplierFamily.STRATEGIC) {
            return "neutral";
        }
        if (parameters.family() == SupplierFamily.ADVERSARIAL) {
            return "negative";
        }
        double mean =
                "conciliatory".equals(parameters.stance())
                        ? 1.0
                        : "aggressive".equals(parameters.stance()) ? -1.0 : 0.0;
        double sigma = parameters.family() == SupplierFamily.STOCHASTIC ? 2.0 : 0.75;
        double sample = mean + random.nextGaussian() * sigma;
        return sample > 0.5 ? "positive" : sample < -0.5 ? "negative" : "neutral";
    }

    private double reservation() {
        return parameters.reservationPrice().amount().doubleValue();
    }

    private double maximum() {
        return parameters.maximumPrice().amount().doubleValue();
    }

    private double range() {
        return Math.max(1.0, maximum() - parameters.minimumPrice().amount().doubleValue());
    }

    /** 数值稳定的 sigmoid：正负分支分别计算，避免 exp 上溢。 */
    private static double sigmoid(double value) {
        return value >= 0 ? 1.0 / (1.0 + Math.exp(-value)) : Math.exp(value) / (1.0 + Math.exp(value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** 家族×立场对应的行为系数：rho(历史让步影响接受)、xi(历史速度影响接受)、lambda2(历史幅度影响让步)，按 stance 取 conciliatory/中立/aggressive 三档。 */
    private record Preset(double rho, double xi, double lambda2) {
        static Preset forFamily(SupplierFamily family, String stance) {
            int index = "conciliatory".equals(stance) ? 0 : "aggressive".equals(stance) ? 2 : 1;
            return switch (family) {
                case CANDID, TACITURN -> from(
                        index,
                        new double[]{0, -0.25, -0.75},
                        new double[]{0.40, 0, -0.50},
                        new double[]{0.30, 0.50, 1.00});
                case EXPRESSIVE, STRATEGIC -> from(
                        index,
                        new double[]{0, -0.75, -1.50},
                        new double[]{0.40, 0, -0.75},
                        new double[]{0.45, 0.90, 1.80});
                case STOCHASTIC -> from(
                        index,
                        new double[]{0, -0.50, -1.10},
                        new double[]{0.35, 0, -0.60},
                        new double[]{0.35, 0.70, 1.40});
                case ADVERSARIAL -> from(
                        index,
                        new double[]{-0.25, -1.25, -2.25},
                        new double[]{0, -0.50, -1.20},
                        new double[]{0.60, 1.40, 2.60});
            };
        }

        private static Preset from(int index, double[] rho, double[] xi, double[] lambda2) {
            return new Preset(rho[index], xi[index], lambda2[index]);
        }
    }
}
