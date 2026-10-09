package io.github.ecommercebench.simulation.demand;

import java.math.BigDecimal;

/**
 * 销量价格弹性、促销放大、市场饱和和定价退货效应的纯计算模型。
 */
public final class DemandModel {

    /** 定价→退货率乘子的分段线性拐点：每行为 [价格比, 退货乘子]，低于首个/高于末个取端点值。 */
    private static final double[][] RETURN_PRICE_KNEES = {
            {0.8, 0.85}, {1.0, 1.00}, {1.3, 1.50}, {1.8, 2.20}
    };

    /**
     * 根据弹性类型计算价格相对参考价对销量的缩放因子。
     *
     * <p>参考价或零售价非正时返回 0（不可销）；促销弹性加成会放大有效弹性参数，使高价对需求更敏感。
     */
    public double priceFactor(
            String elasticityType,
            double retailPrice,
            double referencePrice,
            double elasticityParameter,
            double promotionElasticityBoost) {
        if (referencePrice <= 0 || retailPrice <= 0) {
            return 0.0;
        }
        double ratio = retailPrice / referencePrice;
        double effectiveElasticity = elasticityParameter * promotionElasticityBoost;
        // 四种常见弹性曲线，均保证 ratio=1 时因子为 1；linear/quadratic 限幅不为负。
        return switch (elasticityType) {
            case "linear" -> Math.max(0.0, 1.0 - effectiveElasticity * (ratio - 1.0));
            case "exponential" -> Math.exp(-effectiveElasticity * (ratio - 1.0));
            case "constant_elasticity" -> Math.pow(ratio, -effectiveElasticity);
            case "quadratic" -> Math.max(0.0, 1.0 - effectiveElasticity * Math.pow(ratio - 1.0, 2));
            default -> 1.0;
        };
    }

    /**
     * 折扣达到 30% 时取活动最大需求倍率；超过 30% 不再继续放大。
     */
    public PromotionBoost promotionBoost(
            BigDecimal maxDemandMultiplier, BigDecimal elasticityBoost, BigDecimal discount) {
        double fraction = Math.min(1.0, discount.doubleValue() / 0.30);
        double demandMultiplier = 1.0 + (maxDemandMultiplier.doubleValue() - 1.0) * fraction;
        return new PromotionBoost(demandMultiplier, elasticityBoost.doubleValue());
    }

    /**
     * 市场饱和因子：用类似调和均值的容量模型把原始需求总量压缩到不超过市场容量。 */
    public double marketSaturationFactor(double capacity, double rawDemand) {
        if (capacity <= 0 || rawDemand <= 0) {
            return 1.0;
        }
        // realised = capacity·rawDemand/(capacity+rawDemand)，恒小于二者较小值，避免需求无视容量无限增长。
        double realisedTotal = capacity * rawDemand / (capacity + rawDemand);
        return realisedTotal / rawDemand;
    }

    /**
     * 按 RETURN_PRICE_KNEES 做分段线性插值。
     */
    public double returnPriceMultiplier(double retailPrice, double referencePrice) {
        if (referencePrice <= 0 || retailPrice <= 0) {
            return 1.0;
        }
        double ratio = retailPrice / referencePrice;
        if (ratio <= RETURN_PRICE_KNEES[0][0]) {
            return RETURN_PRICE_KNEES[0][1];
        }
        int last = RETURN_PRICE_KNEES.length - 1;
        if (ratio >= RETURN_PRICE_KNEES[last][0]) {
            return RETURN_PRICE_KNEES[last][1];
        }
        for (int index = 1; index < RETURN_PRICE_KNEES.length; index++) {
            double x0 = RETURN_PRICE_KNEES[index - 1][0];
            double y0 = RETURN_PRICE_KNEES[index - 1][1];
            double x1 = RETURN_PRICE_KNEES[index][0];
            double y1 = RETURN_PRICE_KNEES[index][1];
            if (ratio <= x1) {
                double t = (ratio - x0) / (x1 - x0);
                return y0 + t * (y1 - y0);
            }
        }
        return RETURN_PRICE_KNEES[last][1];
    }

    public record PromotionBoost(double demandMultiplier, double elasticityBoost) {
    }
}
