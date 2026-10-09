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

    public CounterpartKernel(KernelParameters parameters, DeterministicRandom random) {
        this.parameters = parameters;
        this.random = random;
        this.preset = Preset.forFamily(parameters.family(), parameters.stance());
    }

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

    private NegotiationDecision decideResponse(int round, double price) {
        double favourability = (price - reservation()) / range();
        if (favourability >= 0) {
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

    private static double sigmoid(double value) {
        return value >= 0 ? 1.0 / (1.0 + Math.exp(-value)) : Math.exp(value) / (1.0 + Math.exp(value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

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
